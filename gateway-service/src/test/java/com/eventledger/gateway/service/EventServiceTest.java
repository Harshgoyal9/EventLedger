package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.entity.EventType;
import com.eventledger.gateway.metrics.MetricsService;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private MetricsService metricsService;

    private ObjectMapper objectMapper;
    private EventService eventService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        eventService = new EventService(eventRepository, accountServiceClient, objectMapper, metricsService);
    }

    @Test
    @DisplayName("Should process new event successfully")
    void processEvent_success() {
        EventRequest request = createEventRequest("evt-001", "acct-123", "CREDIT", "100.00");

        when(eventRepository.findById("evt-001")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            event.setReceivedAt(Instant.now());
            return event;
        });
        when(accountServiceClient.applyTransaction(eq("acct-123"), any(), any()))
                .thenReturn(new AccountServiceClient.TransactionResult(true, false, null));

        EventResponse response = eventService.processEvent(request, "trace-123");

        assertThat(response.getEventId()).isEqualTo("evt-001");
        assertThat(response.getAccountId()).isEqualTo("acct-123");
        assertThat(response.isDuplicate()).isFalse();

        verify(metricsService).recordEventReceived();
        verify(metricsService).recordEventProcessed();
        verify(eventRepository, times(2)).save(any(Event.class));
    }

    @Test
    @DisplayName("Should detect and return duplicate event")
    void processEvent_duplicate() {
        EventRequest request = createEventRequest("evt-dup", "acct-123", "CREDIT", "100.00");

        Event existingEvent = Event.builder()
                .eventId("evt-dup")
                .accountId("acct-123")
                .type(EventType.CREDIT)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .status(EventStatus.PROCESSED)
                .receivedAt(Instant.now())
                .processedAt(Instant.now())
                .build();

        when(eventRepository.findById("evt-dup")).thenReturn(Optional.of(existingEvent));

        EventResponse response = eventService.processEvent(request, "trace-123");

        assertThat(response.isDuplicate()).isTrue();
        verify(metricsService).recordDuplicateEvent();
        verify(accountServiceClient, never()).applyTransaction(any(), any(), any());
    }

    @Test
    @DisplayName("Should throw exception when Account Service is unavailable")
    void processEvent_accountServiceUnavailable() {
        EventRequest request = createEventRequest("evt-fail", "acct-123", "CREDIT", "100.00");

        when(eventRepository.findById("evt-fail")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountServiceClient.applyTransaction(any(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> eventService.processEvent(request, "trace-123"))
                .isInstanceOf(EventService.AccountServiceUnavailableException.class);

        verify(metricsService).recordEventFailed();
    }

    @Test
    @DisplayName("Should throw exception when circuit breaker is open")
    void processEvent_circuitBreakerOpen() {
        EventRequest request = createEventRequest("evt-cb", "acct-123", "CREDIT", "100.00");

        when(eventRepository.findById("evt-cb")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountServiceClient.applyTransaction(any(), any(), any()))
                .thenThrow(new AccountServiceClient.CircuitBreakerOpenException("Circuit breaker open", null));

        assertThatThrownBy(() -> eventService.processEvent(request, "trace-123"))
                .isInstanceOf(AccountServiceClient.CircuitBreakerOpenException.class);

        verify(metricsService).recordEventFailed();
    }

    @Test
    @DisplayName("Should get event by ID independently of Account Service")
    void getEvent_success() {
        Event event = Event.builder()
                .eventId("evt-get")
                .accountId("acct-123")
                .type(EventType.CREDIT)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .status(EventStatus.PROCESSED)
                .receivedAt(Instant.now())
                .build();

        when(eventRepository.findById("evt-get")).thenReturn(Optional.of(event));

        EventResponse response = eventService.getEvent("evt-get");

        assertThat(response.getEventId()).isEqualTo("evt-get");
        verifyNoInteractions(accountServiceClient);
    }

    @Test
    @DisplayName("Should throw exception when event not found")
    void getEvent_notFound() {
        when(eventRepository.findById("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEvent("non-existent"))
                .isInstanceOf(EventService.EventNotFoundException.class);
    }

    @Test
    @DisplayName("Should get events by account independently of Account Service")
    void getEventsByAccount_success() {
        List<Event> events = List.of(
                createEvent("evt-1", "acct-123", EventType.CREDIT, "100.00", Instant.parse("2026-01-01T10:00:00Z")),
                createEvent("evt-2", "acct-123", EventType.DEBIT, "50.00", Instant.parse("2026-01-02T10:00:00Z"))
        );

        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-123")).thenReturn(events);

        List<EventResponse> responses = eventService.getEventsByAccount("acct-123");

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getEventId()).isEqualTo("evt-1");
        assertThat(responses.get(1).getEventId()).isEqualTo("evt-2");
        verifyNoInteractions(accountServiceClient);
    }

    @Test
    @DisplayName("Should get account balance from Account Service")
    void getAccountBalance_success() {
        Map<String, Object> balanceData = Map.of(
                "accountId", "acct-123",
                "balance", 500.00,
                "currency", "USD"
        );

        when(accountServiceClient.getBalance("acct-123", "trace-123"))
                .thenReturn(new AccountServiceClient.BalanceResult(true, balanceData, null));

        Map<String, Object> result = eventService.getAccountBalance("acct-123", "trace-123");

        assertThat(result.get("balance")).isEqualTo(500.00);
    }

    private EventRequest createEventRequest(String eventId, String accountId, String type, String amount) {
        return EventRequest.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(type)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();
    }

    private Event createEvent(String eventId, String accountId, EventType type, String amount, Instant timestamp) {
        return Event.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(type)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .eventTimestamp(timestamp)
                .status(EventStatus.PROCESSED)
                .receivedAt(Instant.now())
                .build();
    }
}
