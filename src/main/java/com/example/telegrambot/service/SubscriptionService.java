package com.example.telegrambot.service;

import com.example.telegrambot.entity.Subscription;
import com.example.telegrambot.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SubscriptionService {
    private final SubscriptionRepository repo;

    public SubscriptionService(SubscriptionRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void unsubscribe(long chatId, String source) {
        repo.deleteByChatIdAndSource(chatId, source);
    }

    @Transactional
    public boolean subscribe(long chatId, String source) {
        if (repo.existsByChatIdAndSource(chatId, source)) {
            return false;
        }
        repo.save(new Subscription(chatId, source));
        return true;
    }

    @Transactional
    public List<Long> findChatIdsBySource(String source) {
        return repo.findChatIdsBySource(source);
    }

}
