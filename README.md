```json
curl --location --request POST 'http://localhost:5087/workflow/run/PRICE_FILE_GENERATOR' \
--header 'Content-Type: application/json' \
--data ''
```




Docker Compose YML
```docker
services:
  platform-orchestrator:
    image: platform-orchestrator
    container_name: orcha
    build:
      context: ../SAMAY/spriced-platform-ib-ob
      dockerfile: Dockerfile
    ports:
      - "5087:5087" # Host Port : Container Port
    volumes:
      - C:/Logs:/app/logs
      - C:/CodeBase/SAMAY/spriced-platform-ib-ob/workflow-orchestrator/target/:/opt/jars
      - C:/Putty_key:/opt/putty_key
    env_file:
      - C:/CodeBase/Narma/spriced-platform-data-management-layer/.env
    environment:
      # --- FIX: FORCE SPRING BOOT TO USE PORT 5087 ---
      - SERVER_PORT=5087
      
      # override Spring properties
      - PIPELINE_THREADS=50
      - PIPELINE_BATCH_SIZE=1
      - GRPC_CLIENT_PLATFORM_CLIENT_ADDRESS=static://grpc-mock-server:9090
      
    restart: unless-stopped
    networks:
      - platform
    depends_on:
      - grpc-mock-server

  # python-api:
  #   image: python:3.11-slim
  #   container_name: python-api
  #   working_dir: /app
  #   volumes:
  #     - C:/CodeBase/PYTHON_SCRIPT:/app
  #   command: ["python", "multiThreadServer.py"]
  #   ports:
  #     - "9092:9092"
  #   networks:
  #     - platform

  grpc-mock-server:
    image: grpc-mock-server
    container_name: grpc-mock-server
    build:
      # adjust this path if your compose file lives elsewhere
      context: C:/CodeBase/grpc-mock-server
      dockerfile: Dockerfile
    ports:
      - "9090:9090"
    volumes:
      # separate log folder for the mock server
      - C:/Logs/grpc-mock-server:/app/logs
    # add env vars here if your mock server needs any
    environment:
      - SPRING_PROFILES_ACTIVE=default
    restart: unless-stopped
    networks:
      - platform

networks:
  platform:
    driver: bridge
```
