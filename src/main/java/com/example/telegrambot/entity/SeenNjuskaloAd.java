package com.example.telegrambot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
public class SeenNjuskaloAd {

    @Id
    private Long adId;

    private Instant firstSeenAt;

    protected SeenNjuskaloAd() {}

    public SeenNjuskaloAd(Long adId) {
        this.adId = adId;
        this.firstSeenAt = Instant.now();
    }

}

