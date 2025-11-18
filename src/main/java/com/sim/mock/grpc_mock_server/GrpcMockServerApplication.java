package com.sim.mock.grpc_mock_server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standard Spring Boot entry point.
 * The gRPC server will be started automatically by the grpc-server-spring-boot-starter.
 */
@SpringBootApplication
public class GrpcMockServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(GrpcMockServerApplication.class, args);
	}

}
