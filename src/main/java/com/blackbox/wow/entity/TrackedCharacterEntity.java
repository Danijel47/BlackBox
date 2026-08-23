package com.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tracked_character")
public class TrackedCharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private PlayerProfileEntity profile;

    @Column(name = "region", nullable = false, length = 8)
    private String region;

    @Column(name = "realm", nullable = false, length = 128)
    private String realm;

    @Column(name = "character_name", nullable = false, length = 64)
    private String characterName;

    @Column(name = "selected", nullable = false)
    private boolean selected;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected TrackedCharacterEntity() {
    }

    public TrackedCharacterEntity(
            PlayerProfileEntity profile,
            String region,
            String realm,
            String characterName,
            boolean selected
    ) {
        this.profile = profile;
        this.region = region;
        this.realm = realm;
        this.characterName = characterName;
        this.selected = selected;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public PlayerProfileEntity getProfile() {
        return profile;
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

    public boolean isSelected() {
        return selected;
    }

    public boolean isActive() {
        return active;
    }

    public void select() {
        this.selected = true;
        this.active = true;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
