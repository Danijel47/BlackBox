package com.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "wow_token_price_snapshot")
public class WowTokenPriceSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region", nullable = false, length = 8)
    private String region;

    @Column(name = "price_copper", nullable = false)
    private long priceCopper;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected WowTokenPriceSnapshotEntity() {
    }

    public WowTokenPriceSnapshotEntity(
            String region,
            long priceCopper,
            Instant sourceUpdatedAt,
            Instant capturedAt
    ) {
        this.region = region;
        this.priceCopper = priceCopper;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.capturedAt = capturedAt;
    }

    public String getRegion() {
        return region;
    }

    public long getPriceCopper() {
        return priceCopper;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
