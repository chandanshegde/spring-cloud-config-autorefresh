# Spring Cloud Config Auto-Refresh Demo

This project shows how to manage configuration centrally with Spring Cloud, so client apps can get updates without restarting.

## 1. Overview

| Component | Port | What it does |
|-----------|------|--------------|
| Config Server | 8888 | Reads config from Git, serves to clients |
| Consul | 8500 | Service registry |
| RabbitMQ | 5672/15672 | Message broker for Spring Cloud Bus |
| Client App 1 | 8080 | Demo app using config |
| Client App 2 | 8081 | Another demo app |

**Versions:**
- Java 25
- Spring Boot 3.5.0
- Spring Cloud 2025.0.0 (Northfields)
- Gradle 9.3.1
- Consul 1.9
- RabbitMQ 3.x

## 2. Architecture

### 2.1 The Parts

```
┌─────────────────────────────────────────────────────────────────────────────┐
│   ┌──────────────┐     ┌──────────────┐     ┌──────────────┐                │
│   │   Consul     │     │   RabbitMQ   │     │   Git Repo   │                │
│   │   (8500)     │     │  (5672/15672)│     │  (GitHub)    │                │
│   │              │     │              │     │              │                │
│   │  Service     │     │   Spring     │     │   Config     │                │
│   │  Registry    │     │   Cloud Bus  │     │   Files      │                │
│   └──────┬───────┘     └──────┬───────┘     └──────┬───────┘                │
│          │                    │                    │                         │
│          │                    │                    │                         │
│   ┌──────┴────────────────────┴────────────────────┴───────┐                │
│   │                    CONFIG SERVER (8888)                   │                │
│   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │                │
│   │  │  Git        │  │   Consul    │  │   Spring    │    │                │
│   │  │  Backend    │  │  Discovery  │  │   Cloud Bus │    │                │
│   │  └──────────────┘  └──────────────┘  └──────────────┘    │                │
│   └────────────────────────────┬────────────────────────────────┘                │
│                                │                                             │
│           ┌────────────────────┼────────────────────┐                         │
│           │                    │                    │                         │
│   ┌───────┴───────┐    ┌──────┴───────┐    ┌──────┴───────┐                 │
│   │ Client App 1  │    │ Client App 2  │    │ Client App N │                 │
│   │   (8080)      │    │   (8081)      │    │   (808X)      │                 │
│   └───────────────┘    └───────────────┘    └───────────────┘                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 How Clients Get Config at Startup

```
┌─────────────┐                              ┌─────────────────────┐
│   Client    │                              │   Config Server    │
│   App       │                              │     (8888)         │
└──────┬──────┘                              └──────────┬──────────┘
       │                                              │
       │  1. HTTP GET                                 │
       │  spring.config.import                        │
       │  GET /config-client-1/default/main          │
       │  ───────────────────────────────────────►   │
       │                                     ┌───────┴───────┐
       │                                     │  Git Backend │
       │                                     └───────┬───────┘
       │  2. Returns config as JSON                  │
       │◄────────────────────────────────────────────┘
       │
       │  3. App starts with
       │    this config
       ▼
```

### 2.3 How Refresh Works

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐ 
│   GitHub     │     │   Webhook       │     │   Config Server     │    │   Client            │
│   Repo       │     │   Trigger       │     │     (8888)          │    │                     │
└──────┬──────┘     └────────┬────────┘     └──────────┬──────────┘     └──────────┬──────────┘
       │                      │                         │                          |
       │  Git Push            │                         │                           |
       │◄─────────────────────┤                         │                           |
       │                      │  POST /actuator/monitor │                           |
       │                      │ ──────────────────────► │                           |
       │                      │                         │                           |
       │                      │  1. Fetch from Git      │                           |
       │                      │ ─────────────────────►  │                           |
       │                      │                         │                           |
       │                      │  2. Publish to Bus      │                           |
       │                      │ ─────────────────────►  │                           |
       │                      │                         │                           |
       │                      │                   ┌────┴────┐                       |
       │                      │                   │  RabbitMQ│                      |
       │                      │                   │ Exchange │                      |
       │                      │                   │my-config │                      |
       │                      │                   │   -bus   │                      |
       │                      │                   └────┬────┘                       |
       │                      │                        │-------------------------->
       │                                                     │  3. All clients      │
       |                              │                      |     receive          │
       |                              │                      │ ───────────────---──►│
    |                                 │                      │                      ▼
    |                                 │                      │               ┌──────────────────┐
    |                                 │                      │               │ BusAutoConfig   │
    |                                 │                      │               │ auto-calls      │
    |                                 │                      │               │ /refresh        │
    |                                 │                      │               └────────┬─────────┘
    |                                 │                      │                        ▼
    |                                 │                      │               ┌──────────────────┐
    |                                 │                      │               │ @RefreshScope   │
    |                                 │                      │               │ beans reload    │
    |                                 │                      │               └──────────────────┘
```

### 2.4 RabbitMQ / Spring Cloud Bus Details

```
┌────────────────────────────────────────────────────────────────┐
│   RabbitMQ (Fanout Exchange)                                    │
│                                                                 │
│   Exchange: my-config-bus (configurable)                                       │
│   Type: fanout - broadcasts to ALL bound queues               │
│   One-way: Config Server → RabbitMQ → Clients                  │
│                                                                 │
│   ┌─────────────┐         ┌─────────────┐         ┌─────────┐ │
│   │   Config    │         │   RabbitMQ   │         │ Client  │ │
│   │   Server    │ ──────► │   Exchange   │ ──────► │   1    │ │
│   │  (publish)  │ publish │ (fanout)     │  event  │         │ │
│   └─────────────┘         └─────────────┘         └─────────┘ │
│                                 │                             │
│                                 │ broadcast                   │
│                                 ▼                             │
│                           ┌─────────────┐                     │
│                           │   Client    │                     │
│                           │     2       │                     │
│                           └─────────────┘                     │
└────────────────────────────────────────────────────────────────┘
```

## 3. How It Works

### 3.1 Client Startup - Config Import

At startup, clients use `spring.config.import` to fetch config  from the Config Server via HTTP GET:

```yaml
spring:
  config:
    import: optional:configserver:http://localhost:8888
```

This makes an HTTP GET to:
```
http://localhost:8888/{application-name}/{profile}/{label}
# Example: http://localhost:8888/config-client-1/default/main
```

Config Server reads from Git and returns JSON.


### 3.4 Spring Cloud Bus Endpoints

We exposed these endpoints in our Config Server:

| Endpoint | Purpose |
|----------|---------|
| `/actuator/monitor` | Webhook for GitHub/GitLab. When called, fetches latest config from Git and broadcasts to all clients via RabbitMQ |
| `/actuator/busrefresh` | Deprecated. Previously used to trigger refresh across all services |
| `/actuator/busenv` | Broadcasts environment changes to all services. Useful when configuration properties change |

The `/actuator/monitor` is the primary endpoint we use for GitHub webhooks.

### 3. RabbitMQ / Spring Cloud Bus

| Property            | Default Value                             | Our Config                                                                         |
|---------------------|-------------------------------------------|------------------------------------------------------------------------------------|
| **Exchange**        | `springCloudBus`                          | `my-config-bus`                                                                    |
| **Exchange Type**   | `fanout` (broadcast)                      | `fanout`                                                                           |
| **Consumer Queues** | `springCloudBus.anonymous.{random-id}`    | (auto-generated by the individual consumer client apps for their listening queues) |
| **Routing Key**     | `**` (broadcasts to all listening queues) | `**`                                                                               |
| **Communication**   | **One-way** - Server → RabbitMQ → Clients | **One-way**                                                                        |

The Spring Cloud Bus uses a **fanout exchange** - all connected client queues receive every message.
At startup, both the client app services bind their queues to the provided exchange.

### 3.5 Changing the Default Exchange Name (by default it is `springCloudBus`)

To customize the exchange name, add this to ALL services:

```yaml
spring:
  cloud:
    bus:
      destination: my-custom-bus   # Default: springCloudBus
      id: ${spring.application.name}
```

**Important:**  All services need the same `destination` value.

### 3.6 Refresh Flow
```
1. Developer pushes config changes to GitHub

2. Webhook → POST /actuator/monitor (Config Server)

3a. Config server fetches latest from Git and updates its config cache (versioning etc)
3b. Config Server → broadcasts RefreshRemoteApplicationEvent to RabbitMQ
   (Exchange Name: springCloudBus (default, but we have changed to my-cloud-bus), Type: fanout)

4. RabbitMQ broadcasts to all queues → all connected clients receive the event
   (One-way: Server doesn't wait for response)

5. BusAutoConfiguration (in each client, on receiving the event) → automatically calls /actuator/refresh
   (No custom code needed - handled by Spring Cloud Bus)

6. @RefreshScope beans → re-created with new configuration values
```

### 3.7 How `/monitor` Endpoint Sends Messages to RabbitMQ automatically, without any explicit code

**The `/actuator/monitor` endpoint is AUTO-PROVIDED by Spring Cloud Bus**

When we add `spring-cloud-starter-bus-amqp` dependency, Spring Boot auto-configures:

| Component              | Provided By | Purpose |
|------------------------|-------------|---------|
| `/actuator/monitor`    | **Spring Cloud Bus** | Webhook endpoint for GitHub/GitLab webhooks |
| `/actuator/busrefresh` | **Spring Cloud Bus** | Trigger refresh on all services |
| Bus Publisher  bean    | **Spring Cloud Bus** | Publishes events to RabbitMQ |

**We don't need to write any code -** Here's what happens:

```
1. Add dependency to build.gradle:
   implementation 'org.springframework.cloud:spring-cloud-starter-bus-amqp'

2. Add to application.yml:
   spring.cloud.bus.enabled: true

3. Expose the monitor endpoint:
   management.endpoints.web.exposure.include: monitor

4. With this, the /actuator/monitor endpoint now:
   - Accepts POST from GitHub webhook
   - Fetches latest config from Git
   - Publishes RefreshRemoteApplicationEvent to RabbitMQ
   
   All of this is handled by Spring Cloud Bus auto-configuration
```

**How it works internally (for reference):**

```
┌─────────────────────────────────────────────────────────────────┐
│           Spring Cloud Bus Auto-Configuration                   │
│                                                                  │
│  ┌─────────────────┐    ┌─────────────────┐                     │
│  │  BusAuto        │    │  BusProperties │                     │
│  │  Configuration  │    │  (Config)      │                     │
│  └────────┬────────┘    └─────────────────┘                     │
│           │                                                    │
│           ▼                                                    │
│  ┌─────────────────────────────────────────┐                   │
│  │  Provides:                              │                   │
│  │  - /actuator/monitor endpoint           │                   │
│  │  - /actuator/busrefresh endpoint       │                   │
│  │  - BusPublisher bean                   │                   │
│  │  - RabbitMQ channel configuration      │                   │
│  └─────────────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────┘
```

**Key Point:** The Config Server doesn't have ANY code to send messages to RabbitMQ. The `spring-cloud-starter-bus-amqp` library auto-configures everything when we:
1. Add the dependency
2. Enable bus in config
3. Expose the monitor endpoint

### 4.6 How Client Apps Listen for Messages automatically, without any explicit code

Similarly, clients don't need any code to listen for RabbitMQ messages:

```
┌─────────────────────────────────────────────────────────────────┐
│           Client App                                            │
│                                                                  │
│  1. Add dependency:                                             │
│     implementation 'org.springframework.cloud:spring-cloud-     │
│     starter-bus-amqp'                                           │
│                                                                  │
│  2. Add config:                                                 │
│     spring.cloud.bus.enabled: true                              │
│                                                                  │
│  3. Add @RefreshScope to beans:                                │
│     @RefreshScope                                               │
│     class MyController { ... }                                  │
│                                                                  │
│  ────────────────────────────────────────────────────────────  │
│                                                                  │
│  What Spring Cloud Bus auto-configures:                          │
│                                                                  │
│  ┌─────────────────┐    ┌─────────────────┐                     │
│  │  BusAuto        │───►│  RefreshListener│                    │
│  │  Configuration  │    │  (Auto-wired)   │                     │
│  └────────┬────────┘    └────────┬────────┘                     │
│           │                       │                              │
│           ▼                       ▼                              │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  RabbitMQ       │    │  /actuator/refresh│                   │
│  │  Listener       │───►│  auto-invoked    │                    │
│  └─────────────────┘    └─────────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

The `BusAutoConfiguration` creates a listener that:
1. Subscribes to the RabbitMQ exchange
2. Receives `RefreshRemoteApplicationEvent`
3. Automatically calls `/actuator/refresh` internally
4. `@RefreshScope` beans are recreated with new values

**NO CUSTOM CODE NEEDED IN CLIENT APPS EITHER**

## 4. Configuration

### Config Server

```yaml
server:
  port: 8888
spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        git:
          uri: file://./config-repo
    consul:
      host: consul
      port: 8500
  rabbitmq:
    host: rabbitmq
    port: 5672
management:
  endpoints:
    web:
      exposure:
        include: health,refresh,busenv,monitor,info
```

### Config Client

```yaml
spring:
  application:
    name: config-client-1
  config:
    # At startup, makes HTTP GET to:
    # http://config-server:8888/config-client-1/default/main
    import: optional:configserver:http://config-server:8888
  cloud:
    consul:
      discovery:
        service-name: ${spring.application.name}
        prefer-ip-address: false  # Use service name (better for K8s)
    bus:
      # Default exchange: springCloudBus
      destination: my-config-bus  # Custom exchange name
      id: ${spring.application.name}
      enabled: true
rabbitmq:
  host: rabbitmq
  port: 5672
```

## 5. Endpoints

### Config Server

| Endpoint | Method | What it does |
|----------|--------|--------------|
| `/{app}/{profile}` | GET | Get config |
| `/{app}/{profile}/{label}` | GET | Get specific version |
| `/actuator/health` | GET | Health check |
| `/actuator/monitor` | POST | Webhook for GitHub (triggers bus broadcast) |
| `/actuator/busenv` | POST | Broadcast environment changes |

### Client Apps

| Endpoint | Method | What it does |
|----------|--------|--------------|
| `/api/greeting` | GET | Shows current config |
| `/actuator/health` | GET | Health check |
| `/actuator/refresh` | POST | Refresh @RefreshScope beans manually |

## 6. Project Structure

```
spring-cloud-config-autorefresh/
├── config-repo/                    # Simulating Git config repository
│   ├── application.yml
│   ├── config-client-1.yml
│   └── config-client-2.yml
├── config-server/
│   ├── src/main/java/...
│   ├── src/main/resources/application.yml
│   ├── build.gradle
│   └── Dockerfile
├── config-client-1/
│   ├── src/main/java/...
│   ├── src/main/resources/application.yml
│   ├── build.gradle
│   └── Dockerfile
├── config-client-2/
│   ├── src/main/java/...
│   ├── src/main/resources/application.yml
│   ├── build.gradle
│   └── Dockerfile
├── docker-compose.yml
├── settings.gradle
├── build.gradle
├── .github/workflows/
│   └── config-refresh.yml
└── scripts/
    └── trigger-refresh.sh
```

## 7. Running the Demo

### Prerequisites
- Docker Desktop with Docker Compose
- Java 25
- Gradle 9.3.1

### Steps

1. **Start infrastructure only**:
   ```bash
   docker-compose --profile infrastructure up -d
   ```

2. **Start all services**:
   ```bash
   docker-compose --profile full up -d
   ```

3. **Build all projects**:
   ```bash
   ./gradlew build
   ```

4. **Run locally without Docker**:
   ```bash
   # Terminal 1 - Config Server
   cd config-server && ./gradlew bootRun
   
   # Terminal 2 - Client 1
   cd config-client-1 && ./gradlew bootRun
   
   # Terminal 3 - Client 2
   cd config-client-2 && ./gradlew bootRun
   ```

5. **Test Configuration**:
   ```bash
   curl http://localhost:8080/api/greeting
   curl http://localhost:8081/api/greeting
   ```

6. **Trigger Refresh**:
   ```bash
   ./scripts/trigger-refresh.sh
   ```

7. **Verify Update**:
   ```bash
   curl -X POST http://localhost:8080/actuator/refresh
   curl http://localhost:8080/api/greeting  # Should show new value
   ````

## 8. Docker Compose Profiles

| Profile | Services | Description |
|---------|----------|-------------|
| `infrastructure` | consul, rabbitmq | Just the infra services |
| `server` | config-server + infrastructure | Config Server only |
| `clients` | config-client-1, config-client-2 + server | All apps |
| `full` | All services | Complete demo |

### Usage Examples

```bash
# Start only infrastructure
docker-compose --profile infrastructure up -d

# Start with config server
docker-compose --profile server up -d

# Start everything
docker-compose --profile full up -d

# Start specific services
docker-compose up -d consul rabbitmq config-server
```

## 10. References

- [Spring Cloud Config Documentation](https://docs.spring.io/spring-cloud-config/docs/current/reference/html/)
- [Spring Cloud Bus Documentation](https://docs.spring.io/spring-cloud-bus/docs/current/reference/html/)
- [Spring Cloud Consul](https://docs.spring.io/spring-cloud-consul/docs/current/reference/html/)
- [Spring Boot 3.5](https://github.com/spring-projects/spring-boot/releases/tag/v3.5.0)

## 11. Concepts

###  RabbitMQ Concepts

#### Exchanges vs Queues

In RabbitMQ, the concepts map to Kafka as follows:

| RabbitMQ | Kafka | Description |
|----------|-------|-------------|
| Exchange | Topic | Logical container for messages |
| Queue | Partition | Where messages are stored |
| Binding | Consumer group | Defines which queues get which messages |

**Exchanges are lightweight** - they are logical constructs within RabbitMQ, not physical queues. A single RabbitMQ server can handle thousands of exchanges. Think of an exchange like a Kafka topic - it's just a label that producers publish to and consumers subscribe from.

#### Queue Registration at Startup

When a client app starts, Spring Cloud Bus automatically:

1. Creates an anonymous queue (e.g., `springCloudBus.anonymous.{random-id}`)
2. Binds that queue to the exchange
3. Starts listening for messages

We do not define queues manually - Spring Cloud Bus handles this.

```
┌─────────────────────────────────────────────────────────────────┐
│                    CLIENT APP AT STARTUP                          │
│                                                                  │
│  1. App starts                                                    │
│     │                                                              │
│     ▼                                                              │
│  2. Spring Cloud Bus creates queue:                              │
│     springCloudBus.anonymous.abc123                               │
│     │                                                              │
│     ▼                                                              │
│  3. Queue binds to exchange:                                     │
│     queue "abc123" ──bound to──► exchange "my-config-bus"      │
│     │                                                              │
│     ▼                                                              │
│  4. Listener starts waiting for messages                         │
└─────────────────────────────────────────────────────────────────┘
```

#### RabbitMQ Cluster

In our demo, RabbitMQ runs as a **single instance**, not a cluster. In production, RabbitMQ can run as a cluster for high availability, but that is beyond our current scope.

#### Exchange Routing

The exchange uses **bindings** to know which queues should receive messages:

```
┌─────────────────────────────────────────────────────────────────┐
│                      BINDING RULES                               │
│                                                                  │
│  A binding connects a queue to an exchange.                     │
│                                                                  │
│  In our case (fanout exchange):                                 │
│  ═══════════════════════════                                     │
│  Queue "client1-queue" ──bound to──► Exchange "my-config-bus" │
│  Queue "client2-queue" ──bound to──► Exchange "my-config-bus" │
│                                                                  │
│  When a message arrives at the exchange:                         │
│  - Exchange checks its type (fanout)                            │
│  - Fanout sends to ALL bound queues                             │
│  - Both clients receive the message                             │
└─────────────────────────────────────────────────────────────────┘
```

#### Routing Key

In fanout exchanges, the routing key is **ignored** - all queues receive all messages. However, in other exchange types:

| Exchange Type | Routing Key Behavior |
|--------------|---------------------|
| **fanout** | Ignored - all queues get all messages |
| **direct** | Message goes to queues with matching routing key |
| **topic** | Message goes to queues matching wildcard pattern |

In our case, since we use fanout, the routing key is not relevant - every client queue receives every message.

#### Who Defines What

| Who | Defines                                                             |
|-----|---------------------------------------------------------------------|
| Producer (Config Server) | Exchange name and type, and then sends the routing key with message |
| RabbitMQ | Exchange (created automatically when first message sent)            |
| Consumer (Client App) | Queue name and binding (with optional binding key) to exchange      |

The producer does NOT define routing - it just publishes to an exchange. The exchange decides routing based on its type and bindings.

#### Spring Cloud Bus

Spring Cloud Bus provides abstraction over message brokers, similar to how SLF4J provides abstraction over logging frameworks. However, Spring Cloud Bus is primarily tested with RabbitMQ and Kafka. For our demo, we use RabbitMQ.

#### Kafka vs RabbitMQ: Conceptual Mapping

| Kafka Concept | RabbitMQ Equivalent | Function |
|--------------|---------------------|----------|
| Topic (as Entry Point) | Exchange | The post office where producers drop messages |
| Topic (as Storage) | Queue | The physical mailbox where messages sit until picked up |
| Topic Config / Logic | Binding | The "link" or rule that connects an Exchange to a Queue |
| Partition | Queue | RabbitMQ doesn't have partitions; it scales via multiple queues |
| Consumer Group | Competing Consumers | Multiple workers reading from the same RabbitMQ queue |
| Offset | N/A | RabbitMQ doesn't track offsets; it deletes messages after ACK |

#### Key Architectural Differences

1. **Decoupled Routing**
   - In Kafka, the producer targets a specific Topic
   - In RabbitMQ, the producer targets an Exchange
   - The exchange uses Bindings and Routing Keys to decide which Queue (or multiple Queues) should receive the message

2. **Message Lifecycle**
   - **Kafka (Log-based)**: Messages are persistent logs. You can "rewind" the offset to replay data
   - **RabbitMQ (Queue-based)**: Messages are transient buffers. Once a consumer acknowledges a message, it is gone forever

3. **Push vs Pull**
   - **Kafka**: Consumers pull data from the broker at their own pace
   - **RabbitMQ**: The broker pushes data to consumers as fast as they can handle it (managed via basic.qos / prefetch)

#### Mapping Use Cases

**Pub/Sub (One to Many):**
- **Kafka**: Multiple consumer groups subscribe to one Topic
- **RabbitMQ**: One Fanout Exchange bound to multiple individual Queues

**Selective Routing (Filtering):**
- **Kafka**: Consumer receives everything in the topic and filters it via code
- **RabbitMQ**: The Topic Exchange filters messages using wildcards (*, #) before they even reach the queue

#### The "Smart" Component Shift

The biggest difference lies in where the "intelligence" of the system resides:

- **Kafka (Dumb Broker, Smart Consumer)**: The broker just appends to a log. The consumer is responsible for knowing its place (the Offset) and pulling data

- **RabbitMQ (Smart Broker, Dumb Consumer)**: The broker handles complex routing via Exchanges and Bindings. It pushes messages to consumers and deletes them once they are acknowledged

#### Key Differences in Behavior

| Aspect | Kafka | RabbitMQ |
|--------|-------|----------|
| **Persistence** | Topics are append-only logs that keep data for a set time (retention), allowing for message replay | Queues are buffers; messages are generally removed immediately after a consumer acknowledges them |
| **Fan-out (Broadcast)** | Multiple consumer groups simply subscribe to the same topic independently | Must use a Fanout Exchange to copy the message into multiple, independent queues—one for each service |
| **Routing** | Consumers must filter data themselves | Topic Exchange allows complex wildcard filtering (e.g., logs.*.error) at the broker level |
