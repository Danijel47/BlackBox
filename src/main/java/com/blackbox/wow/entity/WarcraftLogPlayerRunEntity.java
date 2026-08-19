package com.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "warcraft_log_player_run")
public class WarcraftLogPlayerRunEntity {

    public static final int CURRENT_METRICS_VERSION = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_key", nullable = false, length = 64)
    private String seasonKey;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "character_name", nullable = false, length = 64)
    private String characterName;

    @Column(name = "report_code", nullable = false, length = 32)
    private String reportCode;

    @Column(name = "report_revision", nullable = false)
    private int reportRevision;

    @Column(name = "report_started_at", nullable = false)
    private Instant reportStartedAt;

    @Column(name = "fight_id", nullable = false)
    private int fightId;

    @Column(name = "dungeon_name", nullable = false, length = 128)
    private String dungeonName;

    @Column(name = "keystone_level", nullable = false)
    private int keystoneLevel;

    @Column(name = "interrupts", nullable = false)
    private int interrupts;

    @Column(name = "deaths", nullable = false)
    private int deaths;

    @Column(name = "key_parse_percentage", precision = 8, scale = 2)
    private BigDecimal keyParsePercentage;

    @Column(name = "parse_percentage", precision = 8, scale = 2)
    private BigDecimal parsePercentage;

    @Column(name = "damage_per_second", precision = 14, scale = 2)
    private BigDecimal damagePerSecond;

    @Column(name = "metrics_version", nullable = false)
    private int metricsVersion;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected WarcraftLogPlayerRunEntity() {
    }

    public WarcraftLogPlayerRunEntity(
            String seasonKey,
            long profileId,
            String characterName,
            String reportCode,
            int reportRevision,
            Instant reportStartedAt,
            int fightId,
            String dungeonName,
            int keystoneLevel,
            int interrupts,
            int deaths,
            BigDecimal parsePercentage,
            BigDecimal keyParsePercentage,
            BigDecimal damagePerSecond
    ) {
        this.seasonKey = seasonKey;
        this.profileId = profileId;
        this.characterName = characterName;
        this.reportCode = reportCode;
        this.reportRevision = reportRevision;
        this.reportStartedAt = reportStartedAt;
        this.fightId = fightId;
        this.dungeonName = dungeonName;
        this.keystoneLevel = keystoneLevel;
        update(
                reportRevision, characterName, dungeonName, keystoneLevel, interrupts, deaths,
                parsePercentage, keyParsePercentage, damagePerSecond
        );
    }

    public void update(
            int reportRevision,
            String characterName,
            String dungeonName,
            int keystoneLevel,
            int interrupts,
            int deaths,
            BigDecimal parsePercentage,
            BigDecimal keyParsePercentage,
            BigDecimal damagePerSecond
    ) {
        this.reportRevision = reportRevision;
        this.characterName = characterName;
        this.dungeonName = dungeonName;
        this.keystoneLevel = keystoneLevel;
        this.interrupts = interrupts;
        this.deaths = deaths;
        this.parsePercentage = parsePercentage;
        this.keyParsePercentage = keyParsePercentage;
        this.damagePerSecond = damagePerSecond;
        this.metricsVersion = CURRENT_METRICS_VERSION;
        this.capturedAt = Instant.now();
    }

    public Long getProfileId() { return profileId; }
    public String getReportCode() { return reportCode; }
    public int getReportRevision() { return reportRevision; }
    public int getFightId() { return fightId; }
    public int getInterrupts() { return interrupts; }
    public int getDeaths() { return deaths; }
    public BigDecimal getParsePercentage() { return parsePercentage; }
    public BigDecimal getKeyParsePercentage() { return keyParsePercentage; }
    public BigDecimal getDamagePerSecond() { return damagePerSecond; }
    public int getMetricsVersion() { return metricsVersion; }
}
