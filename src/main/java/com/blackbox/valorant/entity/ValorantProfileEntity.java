package com.blackbox.valorant.entity;

import com.blackbox.valorant.client.HenrikDevClient.Headshots;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "valorant_profile")
@DynamicUpdate
public class ValorantProfileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName;
    @Column(nullable = false, length = 128)
    private String puuid;
    @Column(name = "riot_id", nullable = false, length = 128)
    private String riotId;
    @Column(nullable = false, length = 16)
    private String region;
    @Column(nullable = false, length = 16)
    private String platform;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "headshot_match_count")
    private Integer headshotMatchCount;
    @Column(name = "head_hits")
    private Long headHits;
    @Column(name = "body_hits")
    private Long bodyHits;
    @Column(name = "leg_hits")
    private Long legHits;
    @Column(name = "headshot_updated_at")
    private Instant headshotUpdatedAt;
    @Column(name = "headshot_attempted_at")
    private Instant headshotAttemptedAt;
    @Column(name = "headshot_refresh_failed", nullable = false)
    private boolean headshotRefreshFailed;
    @Column(name = "headshot_refresh_warning", length = 255)
    private String headshotRefreshWarning;

    protected ValorantProfileEntity() { }

    public ValorantProfileEntity(String profileName, String puuid, String riotId, String region, String platform) {
        this.profileName = profileName;
        this.puuid = puuid;
        this.riotId = riotId;
        this.region = region;
        this.platform = platform;
        this.active = true;
    }

    public Long getId() { return id; }
    public String getProfileName() { return profileName; }
    public String getPuuid() { return puuid; }
    public String getRiotId() { return riotId; }
    public String getRegion() { return region; }
    public String getPlatform() { return platform; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getHeadshotUpdatedAt() { return headshotUpdatedAt; }
    public Instant getHeadshotAttemptedAt() { return headshotAttemptedAt; }
    public boolean isHeadshotRefreshFailed() { return headshotRefreshFailed; }
    public String getHeadshotRefreshWarning() { return headshotRefreshWarning; }

    public Optional<Headshots> savedHeadshots() {
        if (headshotUpdatedAt == null || headshotMatchCount == null || headHits == null || bodyHits == null || legHits == null) {
            return Optional.empty();
        }
        return Optional.of(new Headshots(headshotMatchCount, headHits, bodyHits, legHits));
    }

    public void recordHeadshots(Headshots hits, Instant updatedAt) {
        this.headshotMatchCount = hits.matches();
        this.headHits = hits.headHits();
        this.bodyHits = hits.bodyHits();
        this.legHits = hits.legHits();
        this.headshotUpdatedAt = updatedAt;
        this.headshotAttemptedAt = updatedAt;
        this.headshotRefreshFailed = false;
        this.headshotRefreshWarning = null;
    }

    public void recordHeadshotFailure(Instant attemptedAt, String warning) {
        this.headshotAttemptedAt = attemptedAt;
        this.headshotRefreshFailed = true;
        this.headshotRefreshWarning = warning;
    }
}
