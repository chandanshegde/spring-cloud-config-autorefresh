# Technical Learnings & Discoveries

This document captures technical insights, gotchas, and deep-dive learnings discovered during the development of this Spring Cloud Config auto-refresh demo. These complement the architecture documentation in DESIGN.md.

---

## Table of Contents

1. [Spring Cloud Config Server Profiles](#1-spring-cloud-config-server-profiles)
2. [Configuration Loading & Timing](#2-configuration-loading--timing)
3. [Spring Cloud Bus Internals](#3-spring-cloud-bus-internals)
4. [Logging Architecture](#4-logging-architecture)
5. [Docker & Networking](#5-docker--networking)
6. [Health Checks & Performance](#6-health-checks--performance)
7. [Git Backend vs Native Profile](#7-git-backend-vs-native-profile)
8. [Request Logging Deep Dive](#8-request-logging-deep-dive)
9. [Production Considerations](#9-production-considerations)

---

## 1. Spring Cloud Config Server Profiles

### 1.1 Native vs Git Profile

**Native Profile** (for local development):
```yaml
spring.cloud.config.server.native.search-locations: file:/config-repo
```

**Characteristics:**
- Reads files from local filesystem on **every request** (no caching)
- Changes are visible **immediately** without refresh
- **NOT recommended for production** - no caching means poor performance
- Useful for rapid local development with Docker volumes

**Git Profile** (for production):
```yaml
spring.cloud.config.server.git:
  uri: https://github.com/user/repo.git
  search-paths: config-repo
  refresh-rate: 30
```

**Characteristics:**
- Clones Git repository to `/tmp/config-repo-{random-id}/`
- **Caches the repository** and refreshes based on `refresh-rate` (default: 30 seconds)
- Production-ready with proper caching
- Supports `clone-on-start: true` for fail-fast if repo unreachable
- Can use SSH for private repositories

**Why This Matters:**
We initially had config files in **both** classpath (`src/main/resources/config-repo/`) and filesystem, causing duplicate property sources. The solution was to:
1. Remove classpath config files
2. Use Git profile with current GitHub repository
3. Use `search-paths: config-repo` to point to subdirectory

### 1.2 Git Profile Search Paths

The `search-paths` configuration supports placeholders:
```yaml
search-paths: 
  - config-repo                    # Fixed path
  - config/{application}           # Per-application subdirectories
  - config/{profile}               # Per-profile subdirectories
  - config/{application}/{profile} # Both application and profile
```

**Our setup:**
```yaml
search-paths: config-repo
```

This tells Config Server to look in `https://github.com/user/repo/config-repo/` for all config files.

---

## 2. Configuration Loading & Timing

### 2.1 Client Startup Timing Issue

**Problem:** Clients must start **after** config-server is ready.

**Why:**
```yaml
spring.config.import: optional:configserver:http://config-server:8888
```

The `optional:` prefix means:
- If config-server is **not available** at startup → client starts anyway with default config
- Client **only fetches config at startup** via `spring.config.import`
- Client **does NOT retry** if server is unavailable

**Solution:**
```bash
# Start infrastructure first
docker-compose up -d consul rabbitmq

# Wait for config-server to be healthy
docker-compose up -d config-server
sleep 10

# Now start clients
docker-compose up -d config-client-1 config-client-2
```

**Alternative (not using optional):**
```yaml
spring.config.import: configserver:http://config-server:8888
```
This makes it **mandatory** - client will fail to start if server is unavailable.

### 2.2 Spring Cloud Bus Auto-Refresh Mechanism

**Discovery:** Spring Cloud Bus **automatically invokes `/actuator/refresh`** on all clients when it receives a `RefreshRemoteApplicationEvent`.

**How it works:**
1. `/monitor` endpoint receives webhook (GitHub push event)
2. `PropertyPathNotificationExtractor` extracts changed paths
3. Config Server publishes `RefreshRemoteApplicationEvent` to RabbitMQ
4. **BusAutoConfiguration automatically calls `/actuator/refresh`** on all subscribed clients
5. Clients reload `@RefreshScope` beans

**This means:**
- You don't need to manually call `/actuator/refresh` on each client
- The bus handles broadcasting and refresh automatically
- Communication is **one-way**: Server → RabbitMQ → Clients

### 2.3 Config Server `/monitor` Endpoint

**Key Discovery:** The `/monitor` endpoint is **NOT** under `/actuator/` path.

**Correct paths:**
- `POST /monitor` - Webhook endpoint for Git providers
- `POST /actuator/refresh` - Manual refresh endpoint
- `POST /actuator/busenv` - Send environment change event via bus
- `POST /actuator/monitor` - **This does NOT exist**

**Dependencies required:**
```gradle
implementation 'org.springframework.cloud:spring-cloud-config-monitor'
implementation 'org.springframework.cloud:spring-cloud-starter-bus-amqp'
```

**What `/monitor` does:**
1. Accepts POST requests with Git webhook payloads (GitHub, GitLab, Bitbucket)
2. Extracts changed file paths from webhook JSON
3. Determines which applications need refresh based on changed files
4. Publishes `RefreshRemoteApplicationEvent` via Spring Cloud Bus
5. Returns JSON array of applications to be refreshed: `["config-client-1"]`

**Example webhook payload:**
```bash
curl -X POST http://config-server:8888/monitor \
  -H "X-GitHub-Event: push" \
  -H "Content-Type: application/json" \
  -d '{
    "commits": [{
      "modified": ["config-repo/config-client-1.yml"]
    }]
  }'
```

---

## 3. Spring Cloud Bus Internals

### 3.1 RabbitMQ Exchange and Queue Naming

**Exchange:**
```yaml
spring.cloud.bus.destination: my-config-bus
```
- Creates a **fanout exchange** named `my-config-bus`
- Fanout = broadcasts to **all** bound queues

**Client Queues:**
- Each client creates an **anonymous queue**: `my-config-bus.anonymous.{random-id}`
- Example: `my-config-bus.anonymous.BQz-JEZOTVSKIvTLUMk8Xg`
- Anonymous queues are **deleted when client disconnects**

**Queue Binding:**
```
Exchange: my-config-bus (fanout)
    ├─ Queue: my-config-bus.anonymous.{client1-id} → config-client-1
    ├─ Queue: my-config-bus.anonymous.{client2-id} → config-client-2
    └─ Queue: my-config-bus.anonymous.{server-id}  → config-server
```

### 3.2 One-Way vs Two-Way Communication

**Common Misconception:** Spring Cloud Bus is bidirectional (server ↔ clients).

**Reality:** Spring Cloud Bus is **publish-subscribe** (one-way broadcast):
- Config Server publishes events
- All clients subscribe and receive events
- Clients **do not respond** back to server
- Server has **no knowledge** of which clients received the event

**Use Case:**
- Perfect for **broadcasting** config changes to all instances
- Not suitable for **request-response** patterns
- For request-response, use REST APIs or RPC frameworks

### 3.3 Spring Cloud Bus vs Kafka

| Feature | RabbitMQ (Bus) | Kafka |
|---------|----------------|-------|
| **Message Routing** | Exchange routes to queues | Topic partitions |
| **Consumer Model** | Each queue gets copy (fanout) | Consumer group shares partitions |
| **Message Retention** | Deleted after delivery | Persisted (configurable TTL) |
| **Ordering** | Per-queue ordering | Per-partition ordering |
| **Smart Component** | Broker (RabbitMQ) | Consumer (application) |
| **Best For** | Pub-sub, task queues | Event streaming, log aggregation |

**Why Spring Cloud Bus uses RabbitMQ by default:**
- Simpler setup for pub-sub patterns
- Built-in fanout exchange perfect for broadcasting
- Lower operational complexity for small deployments

---

## 4. Logging Architecture

### 4.1 Tomcat Access Logs to Stdout

**Problem:** By default, Tomcat writes access logs to files in `/tmp/tomcat.{port}.{id}/logs/`, not stdout.

**Solution:**
```yaml
server:
  tomcat:
    accesslog:
      enabled: true
      directory: /dev          # Special directory
      prefix: stdout           # Filename prefix
      suffix: ""               # Empty suffix (no .log extension)
      file-date-format: ""     # No date in filename
```

**How it works:**
- Tomcat writes to: `{directory}/{prefix}{date}{suffix}`
- With our config: `/dev/stdout` (special file that redirects to stdout)
- Logs now appear in `docker logs` output

**Log format:**
```yaml
pattern: "%h %l %u %t \"%r\" %s %b"
```
- `%h` = Remote host (client IP)
- `%l` = Remote logical username (usually `-`)
- `%u` = Authenticated user (or `-`)
- `%t` = Timestamp `[04/Mar/2026:15:58:47 +0000]`
- `%r` = Request line `"GET /actuator/health HTTP/1.1"`
- `%s` = HTTP status code `200`
- `%b` = Bytes sent (response size)

**Example output:**
```
172.19.0.1 - - [04/Mar/2026:15:58:47 +0000] "GET /actuator/health HTTP/1.1" 200 1370
```

### 4.2 Async Logging with Logback

**Why async logging matters:**
- Synchronous logging blocks request threads during I/O
- Can add 1-5ms latency per log statement
- In high-throughput apps, this compounds quickly

**Logback AsyncAppender configuration:**
```xml
<appender name="ASYNC_CONSOLE" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="CONSOLE" />
    <queueSize>512</queueSize>              <!-- Buffer size -->
    <discardingThreshold>0</discardingThreshold>  <!-- Never discard -->
    <neverBlock>false</neverBlock>          <!-- Block if queue full -->
    <maxFlushTime>1000</maxFlushTime>       <!-- Wait 1s on shutdown -->
    <includeCallerData>false</includeCallerData>  <!-- Don't capture line numbers -->
</appender>
```

**Key parameters:**
- `queueSize: 512` - Holds up to 512 log events in memory
- `discardingThreshold: 0` - Never discard logs (set to 20% if logs are less critical)
- `neverBlock: false` - If queue is full, **block the calling thread** (prevents log loss)
- `includeCallerData: false` - Don't capture stack trace for performance (skip class name, method, line number)

**Performance benefit:**
- `log.debug()` returns **immediately** (< 1µs)
- Background thread handles I/O
- Request threads stay responsive

### 4.3 CommonsRequestLoggingFilter Deep Dive

**What it logs:**
- **Two log lines per request:**
  1. `Before request [...]` - Before controller execution
  2. `After request [...]` - After response is ready

**Configuration:**
```java
filter.setIncludeQueryString(true);    // Log ?param=value
filter.setIncludeClientInfo(true);     // Log client IP, session, user
filter.setIncludeHeaders(true);        // Log all request headers
filter.setIncludePayload(true);        // Log request body
filter.setMaxPayloadLength(1000);      // Limit to 1KB
```

**Example output:**
```
REQUEST [GET /actuator/health, client=172.19.0.1, headers=[host:"localhost:8888", ...]]
RESPONSE [GET /actuator/health, client=172.19.0.1, headers=[...]]

REQUEST [POST /monitor, client=172.19.0.1, headers=[x-github-event:"push", ...]]
RESPONSE [POST /monitor, client=172.19.0.1, headers=[...], payload={"test":"data"}]
```

**Important notes:**
- Payload **only appears in RESPONSE line**, not REQUEST line
- Uses `ContentCachingRequestWrapper` which buffers entire request body in memory
- Buffering happens **synchronously** (cannot be made async)
- For GET requests, payload is empty (no body to buffer)

**Performance impact:**
- Minimal for GET requests (no body to buffer)
- Can be significant for large POST/PUT bodies
- Consider disabling in production or only enabling for specific endpoints

### 4.4 Logging Levels for Debugging

**Important loggers:**
```yaml
logging:
  level:
    org.springframework.web.filter.CommonsRequestLoggingFilter: DEBUG  # Request logging
    org.springframework.cloud.config: DEBUG                             # Config server operations
    org.springframework.cloud.bus: DEBUG                               # Bus events
    org.springframework.amqp: DEBUG                                     # RabbitMQ communication
    com.example.configserver: DEBUG                                     # Your application
```

**Why DEBUG is needed:**
- `CommonsRequestLoggingFilter` logs at **DEBUG** level
- Without DEBUG, you won't see request/response logs
- Can enable per-package for targeted debugging

---

## 5. Docker & Networking

### 5.1 Container Service Names vs Localhost

**Problem:** Clients inside Docker cannot connect to `localhost:8888`.

**Why:**
- `localhost` inside a container refers to the **container itself**, not the host
- Each container has its own network namespace

**Solution:** Use **service names** from docker-compose.yml:
```yaml
spring.config.import: configserver:http://config-server:8888  # NOT localhost
spring.rabbitmq.host: rabbitmq                                # NOT localhost
spring.cloud.consul.host: consul                              # NOT localhost
```

**How Docker DNS works:**
- Docker creates a DNS server for each network
- Service names (from `docker-compose.yml`) resolve to container IPs
- Example: `config-server` → `172.19.0.4`

### 5.2 Windows Docker Volume Mounts

**Problem:** Git Bash converts Unix paths to Windows paths.

**Unix path:**
```yaml
volumes:
  - ./config-repo:/config-repo  # Works in Linux/Mac
```

**Git Bash on Windows:**
- Converts `./config-repo` to `C:/Users/.../.../config-repo`
- But Docker on Windows expects: `/c/Users/.../config-repo`
- This causes "path not found" errors

**Solution:** Use full Windows paths:
```yaml
volumes:
  - C:/Blackbox/github/spring-cloud-config-autorefresh/config-repo:/config-repo
```

**Better solution:** Don't use volumes for config with Git profile - let Config Server clone from Git.

### 5.3 Docker Compose Profiles

**Our setup:**
```yaml
services:
  consul:
    profiles: [infrastructure, full]
  
  config-server:
    profiles: [server, full]
    depends_on: [consul, rabbitmq]
  
  config-client-1:
    profiles: [clients, full]
    depends_on: [config-server]
```

**Usage:**
```bash
# Start only infrastructure
docker-compose --profile infrastructure up -d

# Start server (includes infrastructure via depends_on)
docker-compose --profile server up -d

# Start everything
docker-compose --profile full up -d
```

**Why this matters:**
- Allows incremental startup (infra → server → clients)
- Prevents timing issues (clients starting before server ready)
- Useful for development and debugging

---

## 6. Health Checks & Performance

### 6.1 Consul Health Check Causing Repeated Config Loads

**Problem:** Config Server logs "Adding property source" every 10 seconds.

**Why:**
1. Consul calls `/actuator/health` every 10 seconds (default interval)
2. The `configServer` health indicator loads environment to check config server status
3. This triggers "Adding property source" log entry

**Evidence:**
```
2026-03-04T15:58:47.533Z INFO  o.s.c.c.s.e.NativeEnvironmentRepository : Adding property source: Config resource 'file [/tmp/config-repo-.../config-repo/application.yml]'
```

This happens on **every health check**, not just on actual config changes.

**Solutions:**

**Option 1: Disable configServer health indicator**
```yaml
management:
  health:
    config:
      enabled: false
```

**Option 2: Increase Consul health check interval**
```yaml
spring:
  cloud:
    consul:
      discovery:
        health-check-interval: 30s  # Instead of 10s
```

**Option 3: Use simpler health check**
```yaml
spring:
  cloud:
    consul:
      discovery:
        health-check-path: /actuator/health/ping  # Simpler endpoint
```

**Trade-offs:**
- Option 1: Loses config server health visibility
- Option 2: Slower detection of unhealthy instances
- Option 3: Simpler check but less detailed health info

---

## 7. Git Backend vs Native Profile

### 7.1 When to Use Each

**Native Profile:**
```yaml
spring.cloud.config.server.native:
  search-locations: file:/config-repo
```

**Best for:**
- Local development
- Rapid iteration (changes visible immediately)
- No Git repository setup needed
- Docker volumes for config files

**Production concerns:**
- **No caching** - reads from disk on every request
- **Poor performance** under load
- **No version control** built-in
- **No audit trail** of config changes

**Git Profile:**
```yaml
spring.cloud.config.server.git:
  uri: https://github.com/user/repo.git
  search-paths: config-repo
  refresh-rate: 30
  clone-on-start: true
```

**Best for:**
- Production environments
- Version control and audit trail
- Multi-environment support (branches)
- Caching for performance

**Production benefits:**
- **Caching** - clones once, refreshes based on `refresh-rate`
- **Version control** - Git history shows who changed what and when
- **Branches** - Different configs for dev/staging/prod using `{label}` placeholder
- **Security** - Can use SSH keys for private repos

### 7.2 Git Profile Caching Behavior

**How caching works:**
1. First request: Clone repository to `/tmp/config-repo-{random-id}/`
2. Subsequent requests: Serve from cached clone
3. Every `refresh-rate` seconds: Run `git pull` to update cache
4. Manual refresh: Call `/actuator/refresh` or use Spring Cloud Bus

**Config:**
```yaml
spring.cloud.config.server.git:
  refresh-rate: 30  # Check for updates every 30 seconds (default: 0 = always fetch)
```

**Performance impact:**
- `refresh-rate: 0` - Every request triggers `git fetch` (slow, don't use in production)
- `refresh-rate: 30` - Checks for updates every 30 seconds (good balance)
- `refresh-rate: 300` - Checks every 5 minutes (use with webhooks for manual refresh)

**Best practice:**
- Set `refresh-rate: 300` (5 minutes) or higher
- Use `/monitor` endpoint with Git webhooks for immediate updates
- Let Spring Cloud Bus broadcast changes to all clients

### 7.3 Duplicate Property Sources Issue

**Problem we encountered:**
- Config files in **both** classpath (`src/main/resources/config-repo/`) and Git repository
- Config Server loaded **both** sources
- Caused confusing behavior (which source takes precedence?)

**Logs showed:**
```
Adding property source: Config resource 'classpath:/config-repo/application.yml'
Adding property source: Config resource 'file [/tmp/config-repo-.../config-repo/application.yml]'
```

**Solution:**
1. Delete `config-server/src/main/resources/config-repo/` directory
2. Remove from `.gitignore` the top-level `config-repo/` directory
3. Let Git profile be the **single source of truth**

**Why this matters:**
- Classpath resources are **embedded in JAR**
- Changing classpath resources requires rebuild and redeploy
- Git backend allows **runtime config changes** without rebuild

---

## 8. Request Logging Deep Dive

### 8.1 CommonsRequestLoggingFilter vs Tomcat Access Logs

**Tomcat Access Logs:**
```
172.19.0.1 - - [04/Mar/2026:15:58:47 +0000] "GET /actuator/health HTTP/1.1" 200 1370
```

**What it provides:**
- Client IP, timestamp, request line, status, response size
- Standard Apache/nginx log format
- **No request headers or body**
- Logs **after response is sent**

**CommonsRequestLoggingFilter:**
```
REQUEST [GET /actuator/health, client=172.19.0.1, headers=[host:"localhost:8888", user-agent:"curl/8.18.0", ...]]
RESPONSE [GET /actuator/health, client=172.19.0.1, headers=[...]]
```

**What it provides:**
- Request headers (important for debugging auth, webhooks)
- Request body/payload (for POST/PUT requests)
- **Two log lines** - before and after request processing
- Client info (IP, session, user)

**When to use each:**

| Use Case | Tomcat Access Logs | CommonsRequestLoggingFilter |
|----------|-------------------|----------------------------|
| Production metrics | Always on | Too verbose |
| Debugging webhooks | No headers/body | Shows payload |
| Performance analysis | Lightweight | Has overhead |
| Security audit | Standard format | More detail |
| ELK/Loki integration | Parse easily | Complex parsing |

**Best practice:**
- **Always enable** Tomcat access logs (minimal overhead)
- **Conditionally enable** CommonsRequestLoggingFilter for debugging
- **Disable** CommonsRequestLoggingFilter in production (or enable only for specific paths)

### 8.2 ContentCachingRequestWrapper Gotcha

**How CommonsRequestLoggingFilter works internally:**
1. Wraps `HttpServletRequest` with `ContentCachingRequestWrapper`
2. This wrapper **buffers the entire request body** in memory
3. Controller reads from buffer (can read multiple times)
4. Filter reads from buffer to log payload

**Why this matters:**
- Request body is a **stream** - can only be read once normally
- Wrapper allows **multiple reads** by buffering in memory
- This buffering happens **synchronously** (blocks request thread)

**Performance implications:**
```
GET request (no body):     ~0.1ms overhead (just wrapper creation)
POST request (1KB JSON):   ~1ms overhead (buffering + logging)
POST request (1MB JSON):   ~50ms overhead (buffering + logging)
```

**Memory implications:**
- Each concurrent request with body = one buffer in memory
- 100 concurrent 1MB requests = 100MB memory
- Can cause OutOfMemoryError under load

**Mitigation:**
```java
filter.setMaxPayloadLength(1000);  // Limit to 1KB
```

This truncates the logged payload, but **still buffers the entire body** (just logs less).

**Alternative:** Use separate filter that only wraps specific paths:
```java
@Bean
public FilterRegistrationBean<CommonsRequestLoggingFilter> loggingFilter() {
    FilterRegistrationBean<CommonsRequestLoggingFilter> registrationBean = 
        new FilterRegistrationBean<>();
    registrationBean.setFilter(requestLoggingFilter());
    registrationBean.addUrlPatterns("/monitor", "/config/*");  // Only these paths
    return registrationBean;
}
```

### 8.3 Why Payload Only Appears in "After Request"

**Observation:** Payload only shows in RESPONSE line, not REQUEST line.

**Why:**
```
REQUEST [POST /monitor, client=172.19.0.1, headers=[...]]
                                                         ↑ No payload here

RESPONSE [POST /monitor, client=172.19.0.1, headers=[...], payload={"test":"data"}]
                                                                    ↑ Payload here
```

**Reason:**
- `beforeRequest()` is called **before** controller executes
- At this point, request body **hasn't been read yet**
- Controller reads the body during processing
- `afterRequest()` is called **after** controller completes
- Now the wrapper has the full body cached

**Implication:**
- Can't see request payload in real-time
- Must wait for response log to see what was sent
- For debugging, both lines together show the full picture

---

## 9. Production Considerations

### 9.1 Stdout vs File Logs (Containers vs VMs)

**Container Environments (Kubernetes, Docker, ECS):**

**Best practice: Stdout/stderr**
```yaml
# Tomcat access logs
server.tomcat.accesslog.directory: /dev
server.tomcat.accesslog.prefix: stdout

# Logback
<appender-ref ref="ASYNC_CONSOLE" />
```

**Why:**
- Container runtimes automatically capture stdout/stderr
- Kubernetes aggregates logs from all pods
- No volume mounts needed
- Logs don't consume container disk space
- 12-factor app principle

**Log collectors:**
- **Fluentd/Fluent Bit**: Reads from Docker/K8s log files
- **Promtail (Loki)**: Scrapes container logs via K8s API
- **Filebeat**: Reads Docker json-file logs

**VM/Bare Metal Environments:**

**Best practice: File logs with rotation**
```yaml
# Tomcat access logs
server.tomcat.accesslog.directory: /var/log/myapp
server.tomcat.accesslog.prefix: access_log
server.tomcat.accesslog.file-date-format: .yyyy-MM-dd
server.tomcat.accesslog.max-days: 30

# Logback
<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/var/log/myapp/application.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>/var/log/myapp/application.%d{yyyy-MM-dd}.log</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
</appender>
```

**Why:**
- No container runtime to capture stdout
- Need persistent storage for audit/compliance
- Log rotation prevents disk filling
- Standard Unix pattern for services

**Log collectors:**
- **Filebeat**: Tails log files
- **Logstash**: Reads files or receives from Filebeat
- **Promtail**: Tails files on disk

**Comparison:**

| Aspect | Containers (stdout) | VMs (files) |
|--------|-------------------|-------------|
| **Persistence** | Container runtime | Application/logrotate |
| **Collection** | DaemonSet/sidecar | Agent on host |
| **Rotation** | Not needed | Required |
| **Disk usage** | Managed by runtime | Managed by app |
| **Configuration** | Minimal | More complex |

### 9.2 Security Considerations

**Sensitive data in logs:**

**DON'T log:**
- Passwords, API keys, tokens
- Credit card numbers, SSNs
- Personal identifiable information (PII)

**Sanitize headers:**
```java
filter.setIncludeHeaders(true);  // Careful - logs Authorization, Cookie, etc.
```

**Solution:** Custom filter that redacts sensitive headers:
```java
@Override
protected void beforeRequest(HttpServletRequest request, String message) {
    String sanitized = message.replaceAll(
        "authorization:\\[Bearer [^]]+\\]", 
        "authorization:[REDACTED]"
    );
    super.beforeRequest(request, sanitized);
}
```

**Git credentials:**
```yaml
# DON'T commit this to Git
spring.cloud.config.server.git:
  username: ${GIT_USERNAME}  # Environment variable
  password: ${GIT_PASSWORD}  # Environment variable
```

**Better:** Use SSH keys instead of passwords:
```yaml
spring.cloud.config.server.git:
  uri: git@github.com:user/repo.git
  private-key: ${GIT_PRIVATE_KEY}  # Or path to key file
```

### 9.3 Performance Tuning

**RabbitMQ connection pooling:**
```yaml
spring:
  rabbitmq:
    cache:
      connection:
        mode: CONNECTION
        size: 10
    listener:
      simple:
        concurrency: 3
        max-concurrency: 10
```

**Consul health check optimization:**
```yaml
spring:
  cloud:
    consul:
      discovery:
        health-check-interval: 30s        # Reduce frequency
        health-check-path: /actuator/health/ping  # Simpler endpoint
```

**Git backend caching:**
```yaml
spring:
  cloud:
    config:
      server:
        git:
          refresh-rate: 300  # 5 minutes (use webhooks for immediate updates)
          clone-on-start: true  # Fail fast if repo unavailable
          timeout: 10  # Connection timeout in seconds
```

**Async logging tuning:**
```xml
<appender name="ASYNC_CONSOLE" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>1024</queueSize>  <!-- Increase for high throughput -->
    <discardingThreshold>20</discardingThreshold>  <!-- Discard DEBUG logs if 80% full -->
    <neverBlock>true</neverBlock>  <!-- Don't block threads (may lose logs) -->
</appender>
```

### 9.4 Monitoring & Observability

**Essential metrics to monitor:**

**Config Server:**
- `/actuator/health` status
- Config fetch latency (time to serve config)
- Git repository sync errors
- RabbitMQ connection status

**Clients:**
- `/actuator/health` status
- Last config refresh timestamp
- Failed refresh attempts
- RabbitMQ connection status

**RabbitMQ:**
- Queue depth (should be near 0 for ephemeral events)
- Consumer count (one per client instance)
- Message publish rate
- Connection failures

**Spring Boot Actuator metrics:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

**Prometheus scraping:**
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'spring-apps'
    consul_sd_configs:
      - server: 'consul:8500'
    relabel_configs:
      - source_labels: [__meta_consul_service]
        target_label: service
    metrics_path: '/actuator/prometheus'
```

---

## 10. Common Pitfalls & How to Avoid Them

### 10.1 Forgotten `@RefreshScope`

**Problem:** Config changes don't take effect even after refresh.

**Cause:** Beans are not annotated with `@RefreshScope`.

```java
@RestController
public class MyController {
    @Value("${greeting.message}")
    private String message;  // This WON'T refresh
}
```

**Solution:**
```java
@RestController
@RefreshScope  // Add this annotation
public class MyController {
    @Value("${greeting.message}")
    private String message;  // Now this WILL refresh
}
```

**Why:** Spring creates a proxy for `@RefreshScope` beans that can be destroyed and recreated with new config.

### 10.2 Wrong HTTP Method on `/monitor`

**Problem:** Webhook calls fail with 405 Method Not Allowed.

**Cause:** Using GET instead of POST.

```bash
# WRONG
curl -X GET http://config-server:8888/monitor

# CORRECT
curl -X POST http://config-server:8888/monitor \
  -H "Content-Type: application/json" \
  -d '{"commits":[{"modified":["config.yml"]}]}'
```

**Why:** `/monitor` is designed to receive webhook POST requests from Git providers.

### 10.3 Classpath Config Taking Precedence

**Problem:** Changes to Git config don't take effect.

**Cause:** Config files exist in both classpath and Git, classpath takes precedence.

**Evidence:**
```
Adding property source: Config resource 'classpath:/config-repo/application.yml'
Adding property source: Config resource 'file [/tmp/config-repo-.../config-repo/application.yml]'
```

**Solution:** Delete `src/main/resources/config-repo/` directory.

### 10.4 Missing `spring-cloud-config-monitor` Dependency

**Problem:** `/monitor` endpoint returns 404.

**Cause:** Missing dependency.

**Solution:**
```gradle
implementation 'org.springframework.cloud:spring-cloud-config-monitor'
```

**Why:** The `/monitor` endpoint is not part of the core config server - it's an optional module.

### 10.5 Clients Starting Before Server Ready

**Problem:** Clients start with default config instead of fetching from server.

**Cause:** Using `optional:configserver:` and server not ready at client startup.

**Solution:** Either:
1. Start server first, wait, then start clients
2. Remove `optional:` to make it mandatory (client fails if server unavailable)
3. Use depends_on with health checks in docker-compose

### 10.6 Expecting Immediate Refresh with Git Profile

**Problem:** Config changes not visible immediately after Git push.

**Cause:** Git profile caches repository based on `refresh-rate`.

**Solution:** Call `/monitor` endpoint after push (or use GitHub webhook).

**Why:** Config Server doesn't poll Git continuously - it caches and refreshes based on interval or webhook.

---

## 11. Advanced Topics

### 11.1 Encryption & Decryption

Spring Cloud Config supports encrypting sensitive values:

```yaml
# config-repo/application.yml
datasource:
  password: '{cipher}FKSAJDFGYOS8F7GLHAKERGFHLSAJ'
```

**Server-side decryption:**
```yaml
# Config Server
spring:
  cloud:
    config:
      server:
        encrypt:
          enabled: true
encrypt:
  key: mySymmetricKey  # Or use key-store for asymmetric
```

**Client receives decrypted value** - encryption/decryption transparent to client.

### 11.2 Multiple Git Repositories

Different apps can use different repos:

```yaml
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/default/repo.git
          repos:
            team-a:
              pattern: team-a-*
              uri: https://github.com/team-a/config.git
            team-b:
              pattern: team-b-*
              uri: https://github.com/team-b/config.git
```

Clients fetch from repo matching their application name pattern.

### 11.3 Composite Configuration

Combine multiple backends:

```yaml
spring:
  profiles:
    active: composite
  cloud:
    config:
      server:
        composite:
          - type: git
            uri: https://github.com/user/app-config.git
          - type: git
            uri: https://github.com/user/common-config.git
          - type: vault
            host: vault.example.com
            port: 8200
```

Config Server merges sources in order (later sources override earlier ones).

### 11.4 Config First vs Discovery First

**Config First** (our approach):
```yaml
# bootstrap.yml or spring.config.import
spring.config.import: configserver:http://config-server:8888
```
Client fetches config **before** registering with Consul.

**Discovery First:**
```yaml
# Client finds config-server via Consul
spring:
  cloud:
    config:
      discovery:
        enabled: true
        service-id: config-server
```
Client queries Consul for config-server, then fetches config.

**Trade-offs:**
- **Config First**: Simpler, but requires hardcoded config-server URL
- **Discovery First**: More flexible, but circular dependency (need Consul config to find Consul)

---

## 12. Summary of Key Insights

1. **Git profile caches repositories** - use `refresh-rate` and `/monitor` endpoint for updates
2. **Spring Cloud Bus auto-invokes `/actuator/refresh`** - no manual client calls needed
3. **`/monitor` is NOT under `/actuator/`** - it's a regular MVC endpoint at `/monitor`
4. **Async logging reduces latency** - offloads I/O to background threads
5. **Tomcat access logs write to files by default** - use `/dev/stdout` trick for containers
6. **CommonsRequestLoggingFilter buffers request bodies** - has performance/memory cost
7. **Consul health checks can cause noise** - every 10s check triggers config loading
8. **Container networking uses service names** - not `localhost`
9. **Stdout for containers, files for VMs** - different logging strategies for different environments
10. **`@RefreshScope` is required** - beans won't refresh without it

---

## 13. References

- [Spring Cloud Config Documentation](https://docs.spring.io/spring-cloud-config/docs/current/reference/html/)
- [Spring Cloud Bus Documentation](https://docs.spring.io/spring-cloud-bus/docs/current/reference/html/)
- [Logback AsyncAppender](https://logback.qos.ch/manual/appenders.html#AsyncAppender)
- [12-Factor App: Logs](https://12factor.net/logs)
- [RabbitMQ Concepts](https://www.rabbitmq.com/tutorials/amqp-concepts.html)
- [Docker Logging Best Practices](https://docs.docker.com/config/containers/logging/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

---

**Document Version:** 1.0  
**Last Updated:** March 2026  
**Maintained by:** Spring Cloud Config Auto-Refresh Demo Project
