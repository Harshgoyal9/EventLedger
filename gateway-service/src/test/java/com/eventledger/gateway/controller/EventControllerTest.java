package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.entity.EventType;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /events - should submit event successfully and return 201")
    void submitEvent_success() throws Exception {
        when(accountServiceClient.applyTransaction(eq("acct-123"), any(), any()))
                .thenReturn(new AccountServiceClient.TransactionResult(true, false, null));

        EventRequest request = EventRequest.builder()
                .eventId("evt-001")
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("150.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .metadata(Map.of("source", "test"))
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    @DisplayName("POST /events - duplicate event should return 200")
    void submitEvent_duplicate() throws Exception {
        when(accountServiceClient.applyTransaction(eq("acct-123"), any(), any()))
                .thenReturn(new AccountServiceClient.TransactionResult(true, false, null));

        EventRequest request = EventRequest.builder()
                .eventId("evt-dup")
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    @DisplayName("POST /events - validation errors should return 400")
    void submitEvent_validationError() throws Exception {
        EventRequest request = EventRequest.builder()
                .eventId("")
                .accountId("")
                .type("INVALID")
                .amount(new BigDecimal("-100.00"))
                .currency("")
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("POST /events - circuit breaker open should return 503")
    void submitEvent_circuitBreakerOpen() throws Exception {
        when(accountServiceClient.applyTransaction(any(), any(), any()))
                .thenThrow(new AccountServiceClient.CircuitBreakerOpenException("Circuit breaker open", null));

        EventRequest request = EventRequest.builder()
                .eventId("evt-cb")
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.circuitBreaker").value("OPEN"));
    }

    @Test
    @DisplayName("GET /events/{id} - should return event (graceful degradation)")
    void getEvent_success() throws Exception {
        Event event = Event.builder()
                .eventId("evt-get")
                .accountId("acct-123")
                .type(EventType.CREDIT)
                .amount(new BigDecimal("200.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .status(EventStatus.PROCESSED)
                .build();
        eventRepository.save(event);

        mockMvc.perform(get("/events/evt-get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-get"))
                .andExpect(jsonPath("$.accountId").value("acct-123"));
    }

    @Test
    @DisplayName("GET /events/{id} - non-existent event should return 404")
    void getEvent_notFound() throws Exception {
        mockMvc.perform(get("/events/non-existent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /events?account={accountId} - should return events in chronological order (graceful degradation)")
    void getEventsByAccount_success() throws Exception {
        Event event1 = Event.builder()
                .eventId("evt-1")
                .accountId("acct-list")
                .type(EventType.CREDIT)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-01-01T10:00:00Z"))
                .status(EventStatus.PROCESSED)
                .build();

        Event event2 = Event.builder()
                .eventId("evt-2")
                .accountId("acct-list")
                .type(EventType.DEBIT)
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-01-02T10:00:00Z"))
                .status(EventStatus.PROCESSED)
                .build();

        eventRepository.save(event1);
        eventRepository.save(event2);

        mockMvc.perform(get("/events").param("account", "acct-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$[1].eventId").value("evt-2"));
    }

    @Test
    @DisplayName("GET /health - should return health status")
    void health_success() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("gateway-service"))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    @DisplayName("Trace ID should be generated if not provided")
    void traceIdGeneration() throws Exception {
        when(accountServiceClient.applyTransaction(any(), any(), any()))
                .thenReturn(new AccountServiceClient.TransactionResult(true, false, null));

        EventRequest request = EventRequest.builder()
                .eventId("evt-trace")
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("Trace ID should be propagated when provided")
    void traceIdPropagation() throws Exception {
        when(accountServiceClient.applyTransaction(any(), any(), any()))
                .thenReturn(new AccountServiceClient.TransactionResult(true, false, null));

        EventRequest request = EventRequest.builder()
                .eventId("evt-trace-prop")
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "my-custom-trace-id")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "my-custom-trace-id"));
    }
}
