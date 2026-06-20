package com.eventledger.gateway.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MetricsController {

    private final MeterRegistry meterRegistry;

    @GetMapping("/metrics/custom")
    public ResponseEntity<Map<String, Object>> getCustomMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("service", "gateway-service");
        metrics.put("timestamp", Instant.now().toString());

        Map<String, Object> events = new HashMap<>();
        events.put("received", getCounterValue("events.received.total"));
        events.put("processed", getCounterValue("events.processed.total"));
        events.put("failed", getCounterValue("events.failed.total"));
        events.put("duplicate", getCounterValue("events.duplicate.total"));

        metrics.put("events", events);

        Map<String, Object> timing = new HashMap<>();
        timing.put("averageProcessingTimeMs", getTimerMean("events.processing.time"));

        metrics.put("timing", timing);

        return ResponseEntity.ok(metrics);
    }

    private double getCounterValue(String name) {
        try {
            var counter = Search.in(meterRegistry).name(name).counter();
            return counter != null ? counter.count() : 0.0;
        } catch (Exception e) {
            log.debug("Counter {} not found", name);
            return 0.0;
        }
    }

    private double getTimerMean(String name) {
        try {
            var timer = Search.in(meterRegistry).name(name).timer();
            return timer != null ? timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS) : 0.0;
        } catch (Exception e) {
            log.debug("Timer {} not found", name);
            return 0.0;
        }
    }
}
