package com.sim.mock.grpc_mock_server;

// These imports MUST match the 'java_package' option in your .proto file
import com.sim.spriced.platform.grpc.generated.ApplicationRequest;
import com.sim.spriced.platform.grpc.generated.ApplicationResponse;
import com.sim.spriced.platform.grpc.generated.ApplicationServiceGrpc;

// --- NEW IMPORTS ---
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
// --- END NEW IMPORTS ---

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * This is the gRPC service implementation.
 * The @GrpcService annotation tells the Spring Boot starter to
 * register this class as a gRPC service.
 */
@GrpcService
public class MockApplicationService extends ApplicationServiceGrpc.ApplicationServiceImplBase {

    private static final Logger logger = Logger.getLogger(MockApplicationService.class.getName());

    // --- NEW: Jackson mapper to parse the JSON ---
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * This method implements the "BusinessExecution" RPC defined in your service.proto.
     */
    @Override
    public void businessExecution(ApplicationRequest request,
                                  StreamObserver<ApplicationResponse> responseObserver) {

        // 1. Get the payload from the request (this is the large JSON string)
        String payload = request.getPayload();

        // 2. Log the request
        logger.info("====================================================");
        logger.info("gRPC MOCK SERVER: Received gRPC request at " + Instant.now());
        logger.info("Total payload length: " + payload.length());

        // --- NEW: Parse and log the JSON structure ---
        try {
            JsonNode rootNode = mapper.readTree(payload);

            if (rootNode.isArray()) {
                int arraySize = rootNode.size();
                logger.info("PAYLOAD VALIDATION: SUCCESS. Received a valid JSON array.");
                logger.info("PAYLOAD STRUCTURE: Array contains " + arraySize + " elements.");

                // Pretty-print the first element to show its structure
                if (arraySize > 0) {
                    String firstElementPretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode.get(0));
                    logger.info("PAYLOAD EXAMPLE (First Element):\n" + firstElementPretty);
                }
            } else if (rootNode.isObject()) {
                logger.warning("PAYLOAD VALIDATION: SUCCESS. Received a valid JSON object (but expected an array).");
            } else {
                logger.warning("PAYLOAD VALIDATION: SUCCESS. Received valid JSON, but it's not an array or object.");
            }

        } catch (Exception e) {
            // If parsing fails, log the error and the original truncated payload
            logger.severe("PAYLOAD VALIDATION: FAILED. Received invalid JSON. Error: " + e.getMessage());
            logger.info("Payload (first 500 chars): " + payload.substring(0, Math.min(payload.length(), 500)) + "...");
        }
        // --- END NEW LOGIC ---

        logger.info("====================================================");


        // 3. Create the mock JSON response
        String jsonResponse = "{\"status\": \"received_by_grpc_MOCK\", \"received_chars\": " + payload.length() + "}";

        // 4. Build the gRPC ApplicationResponse object
        ApplicationResponse response = ApplicationResponse.newBuilder()
                .setResult(jsonResponse)
                .build();

        // 5. Send the response back to the client
        responseObserver.onNext(response);

        // 6. Complete the RPC call
        responseObserver.onCompleted();
    }
}