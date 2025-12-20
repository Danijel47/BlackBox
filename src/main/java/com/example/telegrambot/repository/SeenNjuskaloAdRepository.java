package com.example.telegrambot.repository;

import com.example.telegrambot.entity.SeenNjuskaloAd;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeenNjuskaloAdRepository extends JpaRepository<SeenNjuskaloAd, Long> {
    boolean existsByAdId(Long adId);
}
