package com.eventledger.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MetricsService {

    private final Counter eventsReceivedCounter;
    private final Counter eventsProcessedCounter;
    private final Counter eventsFailedCounter;
    private final Counter duplicateEventsCounter;
    private final Timer eventProcessingTimer;
    private final AtomicLong activeRequests;

    public MetricsService(MeterRegistry registry) {
        this.eventsReceivedCounter = Counter.builder("events.received.total")
                .description("Total number of events received")
                .tag("service", "gateway")
                .register(registry);

        this.eventsProcessedCounter = Counter.builder("events.processed.total")
                .description("Total number of events processed successfully")
                .tag("service", "gateway")
                .register(registry);

        this.eventsFailedCounter = Counter.builder("events.failed.total")
                .description("Total number of events that failed processing")
                .tag("service", "gateway")
                .register(registry);

        this.duplicateEventsCounter = Counter.builder("events.duplicate.total")
                .description("Total number of duplicate events detected")
                .tag("service", "gateway")
                .register(registry);

        this.eventProcessingTimer = Timer.builder("events.processing.time")
                .description("Time taken to process events")
                .tag("service", "gateway")
                .register(registry);

        this.activeRequests = registry.gauge("events.active.requests",
                new AtomicLong(0));
    }

    public void recordEventReceived() {
        eventsReceivedCounter.increment();
        activeRequests.incrementAndGet();
    }

    public void recordEventProcessed() {
        eventsProcessedCounter.increment();
        activeRequests.decrementAndGet();
    }

    public void recordEventFailed() {
        eventsFailedCounter.increment();
        activeRequests.decrementAndGet();
    }

    public void recordDuplicateEvent() {
        duplicateEventsCounter.increment();
        activeRequests.decrementAndGet();
    }

    public void recordProcessingTime(long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        eventProcessingTimer.record(duration, TimeUnit.MILLISECONDS);
    }
}
