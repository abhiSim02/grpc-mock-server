package com.sim.mock.grpc_mock_server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sim.spriced.platform.grpc.generated.PlatformGrpc;
import com.sim.spriced.platform.grpc.generated.PlatformRequest;
import com.sim.spriced.platform.grpc.generated.PlatformResponse;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@GrpcService
public class MockApplicationService extends PlatformGrpc.PlatformImplBase {

    private static final Logger logger = LoggerFactory.getLogger(MockApplicationService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // Stats Container
    private static final ConcurrentHashMap<String, WorkflowStats> statsMap = new ConcurrentHashMap<>();

    // Concurrency Tracking
    private static final AtomicInteger currentConcurrency = new AtomicInteger(0);
    private static final AtomicInteger peakConcurrencyWindow = new AtomicInteger(0); // Peak in last 5s

    private ExecutorService requestProcessor;
    private ScheduledExecutorService monitorService;

    @Value("${mock.server.threads:10}")
    private int threadCount;

    @PostConstruct
    public void init() {
        logger.info("Initializing Mock Server with {} worker threads.", threadCount);
        this.requestProcessor = Executors.newFixedThreadPool(threadCount);

        // Start the Monitoring Reporter (Runs every 5 seconds)
        this.monitorService = Executors.newSingleThreadScheduledExecutor();
        this.monitorService.scheduleAtFixedRate(this::printReport, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void cleanup() {
        if (requestProcessor != null) requestProcessor.shutdown();
        if (monitorService != null) monitorService.shutdown();
    }

    @Override
    public void platformExecution(PlatformRequest request, StreamObserver<PlatformResponse> responseObserver) {
        // Track Start
        int active = currentConcurrency.incrementAndGet();
        // Update peak if higher
        peakConcurrencyWindow.getAndUpdate(current -> Math.max(current, active));

        requestProcessor.submit(() -> {
            try {
                handleRequest(request, responseObserver);
            } catch (Exception e) {
                logger.error("Error processing request", e);
                responseObserver.onError(e);
            } finally {
                // Track End
                currentConcurrency.decrementAndGet();
            }
        });
    }

    private void handleRequest(PlatformRequest request, StreamObserver<PlatformResponse> responseObserver) {
        String payload = request.getPayload();
        int recordCount = 0;
        String identifier = "Unknown";

        try {
            JsonNode rootNode = mapper.readTree(payload);

            if (rootNode.isObject()) {
                if (rootNode.has("workflowId")) {
                    identifier = rootNode.get("workflowId").asText();
                } else if (rootNode.has("batchId")) {
                    identifier = rootNode.get("batchId").asText().split("-")[0];
                }
                recordCount = 1;

                statsMap.putIfAbsent(identifier, new WorkflowStats());
                statsMap.get(identifier).add(1, recordCount);

            } else if (rootNode.isArray()) {
                recordCount = rootNode.size();
                identifier = "Bulk_Array";
                statsMap.putIfAbsent(identifier, new WorkflowStats());
                statsMap.get(identifier).add(1, recordCount);
            }

        } catch (Exception e) {
            logger.error("Failed to parse payload: {}", e.getMessage());
        }

        String jsonResponse = String.format("{\"status\": \"SUCCESS\", \"id\": \"%s\", \"count\": %d}", identifier, recordCount);
        PlatformResponse response = PlatformResponse.newBuilder().setResult(jsonResponse).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // --- REPORTING LOGIC ---
    private void printReport() {
        if (statsMap.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n==================== MOCK SERVER LIVE MONITOR (Last 5s) ====================\n");
        sb.append(String.format("| %-25s | %-10s | %-12s | %-10s | %-10s |\n", "WORKFLOW ID", "TOT.BATCH", "TOT. RECS", "CURR REC/S", "AVG REC/S"));
        sb.append("|---------------------------|------------|--------------|------------|------------|\n");

        long grandTotalRecords = 0;
        long grandTotalBatches = 0;

        for (Map.Entry<String, WorkflowStats> entry : statsMap.entrySet()) {
            String wf = entry.getKey();
            WorkflowStats s = entry.getValue();

            long now = System.currentTimeMillis();
            long totalRecs = s.totalRecords.get();
            long totalBatches = s.totalBatches.get();

            // Add to Grand Total
            grandTotalRecords += totalRecs;
            grandTotalBatches += totalBatches;

            // Calculate Current Speed (Sliding Window)
            long deltaRecs = totalRecs - s.lastReportedRecords;
            long deltaSec = (now - s.lastReportTime) / 1000;
            if (deltaSec == 0) deltaSec = 1;
            long currentRate = deltaRecs / deltaSec;

            // Calculate Overall Average
            long totalDurationSec = (now - s.startTime) / 1000;
            if (totalDurationSec == 0) totalDurationSec = 1;
            long avgRate = totalRecs / totalDurationSec;

            sb.append(String.format("| %-25s | %-10d | %-12d | %-10d | %-10d |\n",
                    truncate(wf, 25), totalBatches, totalRecs, currentRate, avgRate));

            // Update snapshot for next run
            s.lastReportedRecords = totalRecs;
            s.lastReportTime = now;
        }
        sb.append("|---------------------------|------------|--------------|------------|------------|\n");

        // --- NEW: Grand Total Line ---
        sb.append(String.format("| %-25s | %-10d | %-12d | %-10s | %-10s |\n",
                "** GRAND TOTAL **", grandTotalBatches, grandTotalRecords, "-", "-"));
        sb.append("|---------------------------|------------|--------------|------------|------------|\n");

        int peak = peakConcurrencyWindow.getAndSet(0); // Reset peak for next window
        sb.append(String.format(" CONCURRENCY: Current=%d | Peak(Last 5s)=%d | MaxThreads=%d\n",
                currentConcurrency.get(), peak, threadCount));
        sb.append("==============================================================================\n");

        logger.info(sb.toString());
    }

    private String truncate(String s, int len) {
        if (s.length() <= len) return s;
        return s.substring(0, len-3) + "...";
    }

    // --- Stats Inner Class ---
    private static class WorkflowStats {
        final AtomicLong totalBatches = new AtomicLong(0);
        final AtomicLong totalRecords = new AtomicLong(0);
        final long startTime = System.currentTimeMillis();

        // For sliding window calculation
        long lastReportedRecords = 0;
        long lastReportTime = System.currentTimeMillis();

        void add(int batches, int records) {
            totalBatches.addAndGet(batches);
            totalRecords.addAndGet(records);
        }
    }
}