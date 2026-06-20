package com.eventledger.gateway.integration;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class OutOfOrderEventTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private AccountServiceClient accountServiceClient;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        when(accountServiceClient.applyTransaction(any(), any(), any()))
                .thenReturn(new AccountServiceClient.TransactionResult(true, false, null));
    }

    @Test
    @DisplayName("Events arriving out of order should be listed in chronological order by eventTimestamp")
    void outOfOrderEvents_orderedByEventTimestamp() {
        String accountId = "acct-ooo";
        String traceId = "trace-ooo";

        eventService.processEvent(EventRequest.builder()
                .eventId("evt-3")
                .accountId(accountId)
                .type("CREDIT")
                .amount(new BigDecimal("300.00"))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-05-15T16:00:00Z"))
                .build(), traceId);

        eventService.processEvent(EventRequest.builder()
                .eventId("evt-1")
                .accountId(accountId)
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-05-15T14:00:00Z"))
                .build(), traceId);

        eventService.processEvent(EventRequest.builder()
                .eventId("evt-2")
                .accountId(accountId)
                .type("DEBIT")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-05-15T15:00:00Z"))
                .build(), traceId);

        List<EventResponse> events = eventService.getEventsByAccount(accountId);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).getEventId()).isEqualTo("evt-1");
        assertThat(events.get(0).getEventTimestamp()).isEqualTo(Instant.parse("2026-05-15T14:00:00Z"));
        assertThat(events.get(1).getEventId()).isEqualTo("evt-2");
        assertThat(events.get(1).getEventTimestamp()).isEqualTo(Instant.parse("2026-05-15T15:00:00Z"));
        assertThat(events.get(2).getEventId()).isEqualTo("evt-3");
        assertThat(events.get(2).getEventTimestamp()).isEqualTo(Instant.parse("2026-05-15T16:00:00Z"));
    }

    @Test
    @DisplayName("Events with same timestamp should be handled correctly")
    void eventsWithSameTimestamp() {
        String accountId = "acct-same-ts";
        String traceId = "trace-same";
        Instant sameTimestamp = Instant.parse("2026-05-15T14:00:00Z");

        eventService.processEvent(EventRequest.builder()
                .eventId("evt-a")
                .accountId(accountId)
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(sameTimestamp)
                .build(), traceId);

        eventService.processEvent(EventRequest.builder()
                .eventId("evt-b")
                .accountId(accountId)
                .type("CREDIT")
                .amount(new BigDecimal("200.00"))
                .currency("USD")
                .eventTimestamp(sameTimestamp)
                .build(), traceId);

        List<EventResponse> events = eventService.getEventsByAccount(accountId);

        assertThat(events).hasSize(2);
    }
}
