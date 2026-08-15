package com.example.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "wow_tuning_news_item")
public class WowTuningNewsItemEntity {

    @Id
    @Column(name = "source_guid", nullable = false, length = 512)
    private String sourceGuid;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "notification_sent", nullable = false)
    private boolean notificationSent;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    protected WowTuningNewsItemEntity() {
    }

    public WowTuningNewsItemEntity(
            String sourceGuid,
            String title,
            String url,
            Instant publishedAt,
            boolean notificationSent,
            Instant now
    ) {
        this.sourceGuid = sourceGuid;
        this.title = title;
        this.url = url;
        this.publishedAt = publishedAt;
        this.notificationSent = notificationSent;
        this.firstSeenAt = now;
        this.notifiedAt = notificationSent ? now : null;
    }
}
