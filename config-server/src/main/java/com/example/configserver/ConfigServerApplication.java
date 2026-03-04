package com.example.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server - Central configuration management.
 * 
 * ============== HOW IT WORKS ==============
 * 
 * 1. STARTUP - Serves Config:
 *    - Reads configuration files from Git repository
 *    - Serves them via HTTP: http://localhost:8888/{app}/{profile}/{label}
 *    - Example: http://localhost:8888/config-client-1/default/main
 * 
 * 2. /actuator/monitor - Webhook Endpoint:
 *    - This endpoint is PROVIDED BY Spring Cloud Bus!
 *    - When called (via GitHub webhook or script), it:
 *      a) Pulls latest config from Git
 *      b) Publishes RefreshRemoteApplicationEvent to RabbitMQ
 *    - NO CUSTOM CODE NEEDED - auto-configured by spring-cloud-starter-bus-amqp
 * 
 * 3. RABBITMQ / Spring Cloud Bus:
 *    - Exchange: my-config-bus (configured in application.yml)
 *    - Type: Fanout (broadcasts to ALL connected clients)
 *    - Communication: ONE-WAY (Server → RabbitMQ → Clients)
 * 
 * ============== KEY DEPENDENCIES ==============
 * - spring-cloud-config-server: Provides @EnableConfigServer
 * - spring-cloud-starter-bus-amqp: Auto-configures /monitor endpoint + bus publisher
 * - spring-boot-starter-actuator: Exposes /monitor, /health endpoints
 * - spring-cloud-starter-consul-discovery: Service registration
 */

@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
