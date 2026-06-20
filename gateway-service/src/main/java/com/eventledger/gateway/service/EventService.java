package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.entity.EventType;
import com.eventledger.gateway.metrics.MetricsService;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    @Transactional
    public EventResponse processEvent(EventRequest request, String traceId) {
        long startTime = System.currentTimeMillis();
        metricsService.recordEventReceived();

        log.info("Processing event: eventId={}, accountId={}, traceId={}",
                request.getEventId(), request.getAccountId(), traceId);

        Optional<Event> existingEvent = eventRepository.findById(request.getEventId());
        if (existingEvent.isPresent()) {
            log.info("Duplicate event detected: eventId={}, traceId={}", request.getEventId(), traceId);
            metricsService.recordDuplicateEvent();
            metricsService.recordProcessingTime(startTime);
            return mapToResponse(existingEvent.get(), true);
        }

        Event event = Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(EventType.valueOf(request.getType()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .metadata(serializeMetadata(request.getMetadata()))
                .status(EventStatus.PENDING)
                .build();

        Event savedEvent = eventRepository.save(event);

        try {
            AccountServiceClient.TransactionResult result =
                    accountServiceClient.applyTransaction(request.getAccountId(), request, traceId);

            savedEvent.setStatus(EventStatus.PROCESSED);
            savedEvent.setProcessedAt(Instant.now());
            eventRepository.save(savedEvent);

            log.info("Event processed successfully: eventId={}, traceId={}", request.getEventId(), traceId);
            metricsService.recordEventProcessed();
            metricsService.recordProcessingTime(startTime);
            return mapToResponse(savedEvent, result.duplicate());

        } catch (AccountServiceClient.CircuitBreakerOpenException e) {
            log.warn("Circuit breaker open, cannot process event: eventId={}, traceId={}",
                    request.getEventId(), traceId);
            savedEvent.setStatus(EventStatus.FAILED);
            eventRepository.save(savedEvent);
            metricsService.recordEventFailed();
            metricsService.recordProcessingTime(startTime);
            throw e;
        } catch (Exception e) {
            log.error("Failed to process event: eventId={}, error={}, traceId={}",
                    request.getEventId(), e.getMessage(), traceId);
            savedEvent.setStatus(EventStatus.FAILED);
            eventRepository.save(savedEvent);
            metricsService.recordEventFailed();
            metricsService.recordProcessingTime(startTime);
            throw new AccountServiceUnavailableException("Account service is unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Get a single event by ID - works independently of Account Service (graceful degradation)
     */
    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventId) {
        log.debug("Getting event: eventId={}", eventId);

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));

        return mapToResponse(event, false);
    }

    /**
     * Get all events for an account - works independently of Account Service (graceful degradation)
     * Events are returned in chronological order by eventTimestamp
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        log.debug("Getting events for account: accountId={}", accountId);

        List<Event> events = eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);

        return events.stream()
                .map(e -> mapToResponse(e, false))
                .collect(Collectors.toList());
    }

    /**
     * Get account balance - requires Account Service (no graceful degradation)
     * Will throw AccountServiceUnavailableException if Account Service is down
     */
    public Map<String, Object> getAccountBalance(String accountId, String traceId) {
        log.debug("Getting account balance: accountId={}, traceId={}", accountId, traceId);

        try {
            AccountServiceClient.BalanceResult result = accountServiceClient.getBalance(accountId, traceId);
            return result.data();
        } catch (AccountServiceClient.CircuitBreakerOpenException e) {
            log.warn("Circuit breaker open, cannot get balance: accountId={}, traceId={}",
                    accountId, traceId);
            throw e;
        } catch (Exception e) {
            log.error("Failed to get balance: accountId={}, error={}, traceId={}",
                    accountId, e.getMessage(), traceId);
            throw new AccountServiceUnavailableException("Account service is unavailable: " + e.getMessage(), e);
        }
    }

    private EventResponse mapToResponse(Event event, boolean duplicate) {
        return EventResponse.builder()
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType().name())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .metadata(deserializeMetadata(event.getMetadata()))
                .status(event.getStatus().name())
                .receivedAt(event.getReceivedAt())
                .processedAt(event.getProcessedAt())
                .duplicate(duplicate)
                .build();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadata, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata", e);
            return null;
        }
    }

    public static class EventNotFoundException extends RuntimeException {
        public EventNotFoundException(String message) {
            super(message);
        }
    }

    public static class AccountServiceUnavailableException extends RuntimeException {
        public AccountServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
