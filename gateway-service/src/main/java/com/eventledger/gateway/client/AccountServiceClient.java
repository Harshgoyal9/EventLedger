package com.eventledger.gateway.client;

import com.eventledger.gateway.dto.EventRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Component
public class AccountServiceClient {

    private final RestTemplate restTemplate;
    private final String accountServiceUrl;
    private final CircuitBreaker circuitBreaker;

    public AccountServiceClient(
            RestTemplate restTemplate,
            @Value("${account-service.url}") String accountServiceUrl,
            @Qualifier("accountServiceCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.restTemplate = restTemplate;
        this.accountServiceUrl = accountServiceUrl;
        this.circuitBreaker = circuitBreaker;
    }

    public TransactionResult applyTransaction(String accountId, EventRequest event, String traceId) {
        String url = accountServiceUrl + "/accounts/" + accountId + "/transactions";
        log.debug("Calling Account Service: url={}, eventId={}, traceId={}", url, event.getEventId(), traceId);

        Supplier<TransactionResult> decoratedSupplier = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                () -> executeTransaction(url, accountId, event, traceId)
        );

        try {
            return decoratedSupplier.get();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker is OPEN, rejecting call to Account Service: eventId={}, traceId={}",
                    event.getEventId(), traceId);
            throw new CircuitBreakerOpenException("Account service circuit breaker is open", e);
        }
    }

    private TransactionResult executeTransaction(String url, String accountId, EventRequest event, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("eventId", event.getEventId());
        requestBody.put("type", event.getType());
        requestBody.put("amount", event.getAmount());
        requestBody.put("currency", event.getCurrency());
        requestBody.put("eventTimestamp", event.getEventTimestamp().toString());

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );

            boolean duplicate = response.getStatusCode() == HttpStatus.OK;
            log.info("Account Service response: status={}, duplicate={}, eventId={}, traceId={}",
                    response.getStatusCode(), duplicate, event.getEventId(), traceId);

            return new TransactionResult(true, duplicate, null);
        } catch (RestClientException e) {
            log.error("Failed to call Account Service: eventId={}, error={}, traceId={}",
                    event.getEventId(), e.getMessage(), traceId);
            throw e;
        }
    }

    public BalanceResult getBalance(String accountId, String traceId) {
        String url = accountServiceUrl + "/accounts/" + accountId + "/balance";
        log.debug("Getting balance from Account Service: url={}, traceId={}", url, traceId);

        Supplier<BalanceResult> decoratedSupplier = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                () -> executeGetBalance(url, accountId, traceId)
        );

        try {
            return decoratedSupplier.get();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker is OPEN, rejecting balance request: accountId={}, traceId={}",
                    accountId, traceId);
            throw new CircuitBreakerOpenException("Account service circuit breaker is open", e);
        }
    }

    private BalanceResult executeGetBalance(String url, String accountId, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        if (traceId != null) {
            headers.set("X-Trace-Id", traceId);
        }

        HttpEntity<?> requestEntity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    Map.class
            );

            Map body = response.getBody();
            return new BalanceResult(true, body, null);
        } catch (RestClientException e) {
            log.error("Failed to get balance from Account Service: accountId={}, error={}, traceId={}",
                    accountId, e.getMessage(), traceId);
            throw e;
        }
    }

    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    public record TransactionResult(boolean success, boolean duplicate, String error) {}
    public record BalanceResult(boolean success, Map<String, Object> data, String error) {}

    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
