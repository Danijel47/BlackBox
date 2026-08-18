package com.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "rwf_notification")
public class RaceToWorldFirstNotificationEntity {

    @Id
    @Column(name = "event_key", nullable = false, length = 200)
    private String eventKey;

    @Column(name = "raid_slug", nullable = false, length = 100)
    private String raidSlug;

    @Column(name = "boss_slug", nullable = false, length = 100)
    private String bossSlug;

    @Column(name = "guild_name", nullable = false, length = 200)
    private String guildName;

    @Column(name = "first_defeated_at", nullable = false)
    private Instant firstDefeatedAt;

    @Column(name = "notified_at", nullable = false)
    private Instant notifiedAt;

    protected RaceToWorldFirstNotificationEntity() {
    }

    public RaceToWorldFirstNotificationEntity(
            String eventKey,
            String raidSlug,
            String bossSlug,
            String guildName,
            Instant firstDefeatedAt,
            Instant notifiedAt
    ) {
        this.eventKey = eventKey;
        this.raidSlug = raidSlug;
        this.bossSlug = bossSlug;
        this.guildName = guildName;
        this.firstDefeatedAt = firstDefeatedAt;
        this.notifiedAt = notifiedAt;
    }
}
