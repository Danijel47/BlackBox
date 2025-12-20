package com.example.telegrambot.repository;

import com.example.telegrambot.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    List<Subscription> findBySource(String source);
    void deleteByChatIdAndSource(Long chatId, String source);
    boolean existsByChatIdAndSource(Long chatId, String source);
    @Query("select s.chatId from Subscription s where s.source = :source")
    List<Long> findChatIdsBySource(@Param("source") String source);
}

