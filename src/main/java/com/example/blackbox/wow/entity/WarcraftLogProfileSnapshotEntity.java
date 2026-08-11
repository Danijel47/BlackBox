package com.example.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "warcraft_log_profile_snapshot")
public class WarcraftLogProfileSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_key", nullable = false, length = 64)
    private String seasonKey;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "character_name", nullable = false, length = 64)
    private String characterName;

    @Column(name = "parse_percentage", precision = 8, scale = 2)
    private BigDecimal parsePercentage;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    protected WarcraftLogProfileSnapshotEntity() {
    }

    public WarcraftLogProfileSnapshotEntity(String seasonKey, long profileId, String characterName) {
        this.seasonKey = seasonKey;
        this.profileId = profileId;
        update(characterName, null, null);
    }

    public void update(String characterName, BigDecimal parsePercentage, String error) {
        this.characterName = characterName;
        this.parsePercentage = parsePercentage;
        this.lastError = error == null ? null : error.substring(0, Math.min(512, error.length()));
        this.lastRefreshedAt = Instant.now();
    }

    public Long getProfileId() { return profileId; }
    public BigDecimal getParsePercentage() { return parsePercentage; }
    public String getLastError() { return lastError; }
}
