package com.example.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "raider_io_abandoned_run_dungeon_stat")
public class RaiderIoAbandonedRunDungeonStatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_id", nullable = false)
    private Long importId;

    @Column(name = "dungeon", nullable = false, length = 128)
    private String dungeon;

    @Column(name = "abandoned_runs", nullable = false)
    private int abandonedRuns;

    @Column(name = "live_tracked_runs", nullable = false)
    private int liveTrackedRuns;

    @Column(name = "abandon_percent", nullable = false, precision = 18, scale = 16)
    private BigDecimal abandonPercent;

    protected RaiderIoAbandonedRunDungeonStatEntity() {
    }

    public RaiderIoAbandonedRunDungeonStatEntity(
            Long importId,
            String dungeon,
            int abandonedRuns,
            int liveTrackedRuns,
            BigDecimal abandonPercent
    ) {
        this.importId = importId;
        this.dungeon = dungeon;
        this.abandonedRuns = abandonedRuns;
        this.liveTrackedRuns = liveTrackedRuns;
        this.abandonPercent = abandonPercent;
    }

    public Long getId() {
        return id;
    }

    public Long getImportId() {
        return importId;
    }

    public String getDungeon() {
        return dungeon;
    }

    public int getAbandonedRuns() {
        return abandonedRuns;
    }

    public int getLiveTrackedRuns() {
        return liveTrackedRuns;
    }

    public BigDecimal getAbandonPercent() {
        return abandonPercent;
    }
}
