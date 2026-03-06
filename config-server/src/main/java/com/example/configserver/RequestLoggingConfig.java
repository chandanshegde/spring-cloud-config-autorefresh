package com.example.configserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

/**
 * Request Logging Configuration
 * 
 * Configures CommonsRequestLoggingFilter for debugging HTTP requests.
 * 
 * WHAT IT DOES:
 * - Logs every HTTP request and response with detailed information
 * - Includes request method, URI, query string, client info, headers, and body
 * - Uses DEBUG level logging (must enable DEBUG for org.springframework.web.filter.CommonsRequestLoggingFilter)
 * 
 * PERFORMANCE CONSIDERATIONS:
 * - Uses ContentCachingRequestWrapper which buffers entire request body in memory
 * - This buffering happens synchronously during request processing
 * - Adds overhead to every request, especially those with large bodies
 * - In production, consider enabling only for specific endpoints or disabling entirely
 * 
 * WHAT GETS LOGGED:
 * - Request method, URI, query string
 * - Client IP, session ID, user
 * - Request headers
 * - Request body (payload) - limited to 1KB to prevent memory issues
 * 
 * EXAMPLE OUTPUT:
 * REQUEST [POST /monitor, client=172.19.0.1, headers={X-GitHub-Event=[push],...}]
 * RESPONSE [POST /monitor, client=172.19.0.1, headers={...}, payload={"test":"data",...}]
 * 
 * NOTE: Payload only appears in "After request" (RESPONSE) line, not "Before request" (REQUEST) line
 */
@Configuration
public class RequestLoggingConfig {

    @Bean
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
        
        // Configure the filter
        // Always include query string (useful for debugging)
        filter.setIncludeQueryString(true);
        
        // Always include client info (IP, session, user)
        filter.setIncludeClientInfo(true);
        
        // Always include headers (important for debugging webhooks, auth, etc.)
        filter.setIncludeHeaders(true);
        
        // Include payload (request body) for all requests
        // NOTE: This uses ContentCachingRequestWrapper which buffers entire request body in memory
        // This adds overhead, especially for large request bodies
        // For production, consider only enabling this for specific endpoints or removing it
        filter.setIncludePayload(true);
        
        // Limit payload size to prevent memory issues with large request bodies
        filter.setMaxPayloadLength(1000); // 1KB max - enough for JSON payloads, not too large
        
        // Customize log message format
        filter.setBeforeMessagePrefix("REQUEST [");
        filter.setBeforeMessageSuffix("]");
        filter.setAfterMessagePrefix("RESPONSE [");
        filter.setAfterMessageSuffix("]");
        
        return filter;
    }
}
