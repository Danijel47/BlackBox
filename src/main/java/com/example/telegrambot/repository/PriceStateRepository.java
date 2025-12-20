package com.example.telegrambot.repository;

import com.example.telegrambot.entity.PriceState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceStateRepository extends JpaRepository<PriceState, String> {}

