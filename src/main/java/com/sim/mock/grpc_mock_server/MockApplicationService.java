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

    private static final ConcurrentHashMap<String, WorkflowStats> statsMap = new ConcurrentHashMap<>();
    private static final AtomicInteger currentConcurrency = new AtomicInteger(0);
    private static final AtomicInteger peakConcurrencyWindow = new AtomicInteger(0);

    // Global counter to control payload logging volume
    private static final AtomicLong globalRequestCounter = new AtomicLong(0);

    private ExecutorService requestProcessor;
    private ScheduledExecutorService monitorService;
    private boolean wasActiveLastRun = false;

    @Value("${mock.server.threads:10}")
    private int threadCount;

    @Value("${mock.server.report.interval:10}")
    private int reportInterval;

    @PostConstruct
    public void init() {
        logger.info("Initializing Mock Server with {} threads. Report Interval: {}s", threadCount, reportInterval);
        this.requestProcessor = Executors.newFixedThreadPool(threadCount);
        this.monitorService = Executors.newSingleThreadScheduledExecutor();
        this.monitorService.scheduleAtFixedRate(this::printReport, reportInterval, reportInterval, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void cleanup() {
        if (requestProcessor != null) requestProcessor.shutdown();
        if (monitorService != null) monitorService.shutdown();
    }

    @Override
    public void platformExecution(PlatformRequest request, StreamObserver<PlatformResponse> responseObserver) {
        int active = currentConcurrency.incrementAndGet();
        peakConcurrencyWindow.getAndUpdate(current -> Math.max(current, active));

        requestProcessor.submit(() -> {
            try {
                handleRequest(request, responseObserver);
            } catch (Exception e) {
                logger.error("Error processing request", e);
                responseObserver.onError(e);
            } finally {
                currentConcurrency.decrementAndGet();
            }
        });
    }

    private void handleRequest(PlatformRequest request, StreamObserver<PlatformResponse> responseObserver) {
        String payload = request.getPayload();
        long reqNum = globalRequestCounter.incrementAndGet();

        int recordCount = 0;
        String identifier = "Unknown";
        String payloadType = "UNKNOWN";

        try {
            JsonNode rootNode = mapper.readTree(payload);

            if (rootNode.isObject()) {
                // --- CORRECT CASE: Received a Single Envelope ---
                payloadType = "SINGLE_OBJECT";

                if (rootNode.has("workflowId")) {
                    identifier = rootNode.get("workflowId").asText();
                } else if (rootNode.has("batchId")) {
                    identifier = rootNode.get("batchId").asText().split("-")[0];
                }

                // For a single envelope, record count is 1
                recordCount = 1;

                statsMap.putIfAbsent(identifier, new WorkflowStats());
                statsMap.get(identifier).add(1, recordCount);

            } else if (rootNode.isArray()) {
                // --- WARNING CASE: Received a List (Bulk) ---
                payloadType = "ARRAY_LIST";
                recordCount = rootNode.size();
                identifier = "Bulk_Array";

                statsMap.putIfAbsent(identifier, new WorkflowStats());
                statsMap.get(identifier).add(1, recordCount);
            }

            // --- SAMPLING LOGGER: Log Payload for inspection ---
            // Log first 5 requests, then every 5000th request
            if (reqNum <= 5 || reqNum % 5000 == 0) {
                logger.info("\n>>> [MOCK INSPECT] Request #{}. Type: {}. Identifier: {}. Payload (truncated): {}",
                        reqNum, payloadType, identifier, truncate(payload, 1000));
            }
            // Warn if we received an Array when we expected Object
            if ("ARRAY_LIST".equals(payloadType) && reqNum <= 10) {
                logger.warn(">>> [MOCK WARNING] Received JSON ARRAY! Expected SINGLE OBJECT. Check Client!");
            }

        } catch (Exception e) {
            logger.error("Payload parse error: {}", e.getMessage());
        }

        String jsonResponse = String.format("{\"status\":\"SUCCESS\",\"id\":\"%s\",\"count\":%d}", identifier, recordCount);
        PlatformResponse response = PlatformResponse.newBuilder().setResult(jsonResponse).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // --- OPTIMIZED REPORTING (Unchanged) ---
    private void printReport() {
        if (statsMap.isEmpty()) return;

        boolean anyActivity = false;
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();

        long grandTotalRecords = 0;
        long grandTotalBatches = 0;
        long grandTotalSpeed = 0;

        for (WorkflowStats s : statsMap.values()) {
            long totalRecs = s.totalRecords.get();
            long deltaRecs = totalRecs - s.lastReportedRecords;
            if (deltaRecs > 0) anyActivity = true;
        }

        if (!anyActivity && !wasActiveLastRun) {
            return;
        }

        sb.append("\n=== MOCK SERVER MONITOR (Last ").append(reportInterval).append("s) ===\n");
        sb.append(String.format("| %-25s | %-10s | %-12s | %-10s | %-10s |\n", "WORKFLOW ID", "BATCHES", "RECORDS", "CURR/s", "AVG/s"));
        sb.append("|---------------------------|------------|--------------|------------|------------|\n");

        for (Map.Entry<String, WorkflowStats> entry : statsMap.entrySet()) {
            String wf = entry.getKey();
            WorkflowStats s = entry.getValue();

            long totalRecs = s.totalRecords.get();
            long totalBatches = s.totalBatches.get();
            grandTotalRecords += totalRecs;
            grandTotalBatches += totalBatches;

            long deltaRecs = totalRecs - s.lastReportedRecords;
            long deltaSec = (now - s.lastReportTime) / 1000;
            if (deltaSec == 0) deltaSec = 1;
            long currentRate = deltaRecs / deltaSec;
            grandTotalSpeed += currentRate;

            long durationSec = (now - s.startTime) / 1000;
            if (durationSec == 0) durationSec = 1;
            long avgRate = totalRecs / durationSec;

            sb.append(String.format("| %-25s | %-10d | %-12d | %-10d | %-10d |\n",
                    truncate(wf, 25), totalBatches, totalRecs, currentRate, avgRate));

            s.lastReportedRecords = totalRecs;
            s.lastReportTime = now;
        }

        sb.append("|---------------------------|------------|--------------|------------|------------|\n");
        sb.append(String.format("| %-25s | %-10d | %-12d | %-10d | %-10s |\n",
                "** TOTAL **", grandTotalBatches, grandTotalRecords, grandTotalSpeed, "-"));

        int peak = peakConcurrencyWindow.getAndSet(0);
        sb.append(String.format(" THREADS: Active=%d | Peak=%d | Pool=%d\n",
                currentConcurrency.get(), peak, threadCount));

        if (!anyActivity && wasActiveLastRun) {
            sb.append(" [STATUS: IDLE - Processing Complete]\n");
        }
        sb.append("============================================================================\n");

        logger.info(sb.toString());
        wasActiveLastRun = anyActivity;
    }

    private String truncate(String s, int len) {
        if (s == null) return "";
        if (s.length() <= len) return s;
        return s.substring(0, len-3) + "...";
    }

    private static class WorkflowStats {
        final AtomicLong totalBatches = new AtomicLong(0);
        final AtomicLong totalRecords = new AtomicLong(0);
        final long startTime = System.currentTimeMillis();
        long lastReportedRecords = 0;
        long lastReportTime = System.currentTimeMillis();

        void add(int batches, int records) {
            totalBatches.addAndGet(batches);
            totalRecords.addAndGet(records);
        }
    }
}