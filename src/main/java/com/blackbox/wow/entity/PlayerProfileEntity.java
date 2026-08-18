package com.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "player_profile")
public class PlayerProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_name", nullable = false, length = 64)
    private String profileName;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "season_recap_enabled", nullable = false)
    private boolean seasonRecapEnabled;

    @Column(name = "vault_watch_enabled", nullable = false)
    private boolean vaultWatchEnabled;

    @Column(name = "title_watch_enabled", nullable = false)
    private boolean titleWatchEnabled;

    @Column(name = "title_zero_point_one_watch_enabled", nullable = false)
    private boolean titleZeroPointOneWatchEnabled;

    protected PlayerProfileEntity() {
    }

    public PlayerProfileEntity(String profileName, int displayOrder) {
        this.profileName = profileName;
        this.displayOrder = displayOrder;
        this.active = true;
        this.seasonRecapEnabled = true;
        this.vaultWatchEnabled = true;
        this.titleWatchEnabled = true;
        this.titleZeroPointOneWatchEnabled = false;
    }

    public Long getId() {
        return id;
    }

    public String getProfileName() {
        return profileName;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSeasonRecapEnabled() {
        return seasonRecapEnabled;
    }

    public boolean isVaultWatchEnabled() {
        return vaultWatchEnabled;
    }

    public boolean isTitleWatchEnabled() {
        return titleWatchEnabled;
    }

    public boolean isTitleZeroPointOneWatchEnabled() {
        return titleZeroPointOneWatchEnabled;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
