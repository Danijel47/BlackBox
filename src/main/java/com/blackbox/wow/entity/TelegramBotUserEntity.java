package com.blackbox.wow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "telegram_bot_user")
public class TelegramBotUserEntity {

    @Id
    @Column(name = "telegram_user_id", nullable = false)
    private Long telegramUserId;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TelegramBotUserEntity() {
    }

    public TelegramBotUserEntity(long telegramUserId, String displayName) {
        this.telegramUserId = telegramUserId;
        this.displayName = displayName;
        this.active = true;
        this.updatedAt = Instant.now();
    }

    public Long getTelegramUserId() {
        return telegramUserId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isActive() {
        return active;
    }

    public void update(String displayName, boolean active) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        this.active = active;
        this.updatedAt = Instant.now();
    }
}
