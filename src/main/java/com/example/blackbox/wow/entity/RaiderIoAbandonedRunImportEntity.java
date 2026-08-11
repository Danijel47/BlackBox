package com.example.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "raider_io_abandoned_run_import")
public class RaiderIoAbandonedRunImportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region", nullable = false, length = 8)
    private String region;

    @Column(name = "realm", nullable = false, length = 128)
    private String realm;

    @Column(name = "character_name", nullable = false, length = 64)
    private String characterName;

    @Column(name = "season_slug", nullable = false, length = 64)
    private String seasonSlug;

    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @Column(name = "grouping_dimension", nullable = false, length = 32)
    private String groupingDimension;

    @Column(name = "stat_type", nullable = false, length = 64)
    private String statType;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    protected RaiderIoAbandonedRunImportEntity() {
    }

    public RaiderIoAbandonedRunImportEntity(
            String region,
            String realm,
            String characterName,
            String seasonSlug,
            String scope,
            String groupingDimension,
            String statType,
            Instant generatedAt,
            String sourceUrl,
            int rowCount
    ) {
        this.region = region;
        this.realm = realm;
        this.characterName = characterName;
        this.seasonSlug = seasonSlug;
        this.scope = scope;
        this.groupingDimension = groupingDimension;
        this.statType = statType;
        this.generatedAt = generatedAt;
        this.importedAt = Instant.now();
        this.sourceUrl = sourceUrl;
        this.rowCount = rowCount;
    }

    public Long getId() {
        return id;
    }

    public String getRegion() {
        return region;
    }

    public String getRealm() {
        return realm;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getSeasonSlug() {
        return seasonSlug;
    }

    public String getScope() {
        return scope;
    }

    public String getGroupingDimension() {
        return groupingDimension;
    }

    public String getStatType() {
        return statType;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public int getRowCount() {
        return rowCount;
    }
}
