# Use Java 17 base image
FROM eclipse-temurin:17-jdk

# Set working directory
WORKDIR /app

# Copy the built jar into the container
# (update the jar name if your pom has a different artifactId/version)
COPY target/grpc-mock-server-0.0.1-SNAPSHOT.jar grpc-mock-server.jar

# Create logs directory
RUN mkdir -p /app/logs

# Expose the gRPC port (your app logs show 9090)
EXPOSE 9090

# Optional: small delay if it depends on other services; else you can drop "sleep 10 &&"
CMD ["sh", "-c", "sleep 10 && java -jar grpc-mock-server.jar > /app/logs/grpc-mock-server.log 2>&1"]
