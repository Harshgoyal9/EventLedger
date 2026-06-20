package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submitEvent(
            @Valid @RequestBody EventRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        String effectiveTraceId = traceId != null ? traceId : UUID.randomUUID().toString();
        log.info("Received event submission: eventId={}, accountId={}, traceId={}",
                request.getEventId(), request.getAccountId(), effectiveTraceId);

        EventResponse response = eventService.processEvent(request, effectiveTraceId);

        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<EventResponse> getEvent(
            @PathVariable String eventId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        String effectiveTraceId = traceId != null ? traceId : UUID.randomUUID().toString();
        log.info("Received get event request: eventId={}, traceId={}", eventId, effectiveTraceId);

        EventResponse response = eventService.getEvent(eventId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        String effectiveTraceId = traceId != null ? traceId : UUID.randomUUID().toString();
        log.info("Received get events by account request: accountId={}, traceId={}", accountId, effectiveTraceId);

        List<EventResponse> events = eventService.getEventsByAccount(accountId);
        return ResponseEntity.ok(events);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getAccountBalance(
            @PathVariable String accountId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        String effectiveTraceId = traceId != null ? traceId : UUID.randomUUID().toString();
        log.info("Received get balance request: accountId={}, traceId={}", accountId, effectiveTraceId);

        Map<String, Object> balance = eventService.getAccountBalance(accountId, effectiveTraceId);
        return ResponseEntity.ok(balance);
    }
}
