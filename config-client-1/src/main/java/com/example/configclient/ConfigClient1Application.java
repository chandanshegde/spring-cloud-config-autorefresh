package com.example.configclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo client application that consumes config from Spring Cloud Config Server.
 * 
 * ============== HOW IT WORKS ==============
 * 
 * 1. STARTUP - Config Import:
 *    The client uses spring.config.import to fetch config at startup:
 *    HTTP GET to: http://config-server:8888/{app-name}/{profile}/{label}
 *    Example: http://localhost:8888/config-client-1/default/main
 *    See: spring.config.import in application.yml
 * 
 * 2. @RefreshScope:
 *    Marks beans to be re-created when /actuator/refresh is called.
 *    Only @RefreshScope beans get new values - regular beans don't.
 * 
 * 3. RABBITMQ / Spring Cloud Bus:
 *    - Default Exchange: springCloudBus (fanout type)
 *    - Communication: ONE-WAY (Config Server → RabbitMQ → Clients)
 *    - When Config Server /monitor is called, it broadcasts to RabbitMQ
 *    - BusAutoConfiguration auto-subscribes and calls /actuator/refresh
 *    - NO CUSTOM CODE NEEDED!
 * 
 * 4. Changing the Topic:
 *    Set spring.cloud.bus.destination in application.yml
 *    All services must use the same destination to receive broadcasts.
 * 
 * ============== KEY DEPENDENCIES ==============
 * - spring-cloud-starter-config: Config Client
 * - spring-cloud-starter-bus-amqp: RabbitMQ listener (auto-configured)
 * - spring-boot-starter-actuator: Provides /refresh endpoint
 * - spring-cloud-starter-consul-discovery: Service registration
 */
@SpringBootApplication
public class ConfigClient1Application {

    public static void main(String[] args) {
        SpringApplication.run(ConfigClient1Application.class, args);
    }
}

@RestController
@RefreshScope
class GreetingController {

    @Value("${greeting.name:Default}")
    private String name;

    @Value("${greeting.message:Hello}")
    private String message;

    @Value("${custom.property:default}")
    private String customProperty;

    @GetMapping("/api/greeting")
    public String greeting() {
        return message + " " + name + " | Custom: " + customProperty;
    }

    @GetMapping("/api/config")
    public java.util.Map<String, String> config() {
        return java.util.Map.of(
            "name", name,
            "message", message,
            "customProperty", customProperty
        );
    }
}
