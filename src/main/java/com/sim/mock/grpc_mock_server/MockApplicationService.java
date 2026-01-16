package com.sim.mock.grpc_mock_server;

import com.sim.spriced.platform.grpc.generated.PlatformRequest;
import com.sim.spriced.platform.grpc.generated.PlatformResponse;
import com.sim.spriced.platform.grpc.generated.PlatformGrpc; // Changed from ApplicationServiceGrpc

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import java.time.Instant;
import java.util.logging.Logger;

@GrpcService
public class MockApplicationService extends PlatformGrpc.PlatformImplBase { // Changed base class

    private static final Logger logger = Logger.getLogger(MockApplicationService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    // Changed method name to match .proto
    public void platformExecution(PlatformRequest request,
                                  StreamObserver<PlatformResponse> responseObserver) {

        String payload = request.getPayload();

        logger.info("====================================================");
        logger.info("gRPC MOCK SERVER: Received PlatformExecution request");
        // ... (rest of your logic remains the same) ...

        String jsonResponse = "{\"status\": \"received_by_grpc_MOCK\"}";

        PlatformResponse response = PlatformResponse.newBuilder()
                .setResult(jsonResponse)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}