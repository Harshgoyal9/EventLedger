package com.eventledger.gateway.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_event_account", columnList = "accountId"),
    @Index(name = "idx_event_timestamp", columnList = "eventTimestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @Id
    private String eventId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EventType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Instant eventTimestamp;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        receivedAt = Instant.now();
    }
}
