package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final AccountServiceClient accountServiceClient;

    @Value("${account-service.url}")
    private String accountServiceUrl;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        log.debug("Health check requested, traceId={}", traceId);

        Map<String, Object> response = new HashMap<>();
        response.put("service", "gateway-service");
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());

        Map<String, Object> database = checkDatabase();
        response.put("database", database);

        Map<String, Object> accountService = checkAccountService();
        response.put("accountService", accountService);

        Map<String, Object> circuitBreaker = checkCircuitBreaker();
        response.put("circuitBreaker", circuitBreaker);

        if ("DOWN".equals(database.get("status")) || "DOWN".equals(accountService.get("status"))) {
            response.put("status", "DEGRADED");
        }

        if ("OPEN".equals(circuitBreaker.get("state"))) {
            response.put("status", "DEGRADED");
        }

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> database = new HashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            database.put("status", "UP");
            database.put("type", "H2");
        } catch (Exception e) {
            log.error("Database health check failed", e);
            database.put("status", "DOWN");
            database.put("error", e.getMessage());
        }
        return database;
    }

    private Map<String, Object> checkAccountService() {
        Map<String, Object> accountService = new HashMap<>();
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    accountServiceUrl + "/health", Map.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                accountService.put("status", "UP");
            } else {
                accountService.put("status", "DOWN");
            }
        } catch (Exception e) {
            log.warn("Account service health check failed: {}", e.getMessage());
            accountService.put("status", "DOWN");
            accountService.put("error", "Unable to connect to account service");
        }
        return accountService;
    }

    private Map<String, Object> checkCircuitBreaker() {
        Map<String, Object> circuitBreaker = new HashMap<>();
        CircuitBreaker.State state = accountServiceClient.getCircuitBreakerState();
        circuitBreaker.put("name", "accountService");
        circuitBreaker.put("state", state.name());
        return circuitBreaker;
    }
}
