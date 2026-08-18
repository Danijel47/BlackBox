package com.blackbox.wow.service;

import com.blackbox.wow.entity.TelegramBotUserEntity;
import com.blackbox.wow.repository.TelegramBotUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TelegramBotUserService {

    private final TelegramBotUserRepository repository;

    public TelegramBotUserService(TelegramBotUserRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean isActive(long telegramUserId) {
        return repository.existsByTelegramUserIdAndActiveTrue(telegramUserId);
    }

    @Transactional(readOnly = true)
    public List<TelegramBotUserEntity> users() {
        return repository.findAllByOrderByDisplayNameAscTelegramUserIdAsc();
    }

    @Transactional
    public void addOrEnable(long telegramUserId, String displayName) {
        if (telegramUserId <= 0) {
            throw new IllegalArgumentException("Telegram user ID must be a positive number.");
        }
        TelegramBotUserEntity user = repository.findById(telegramUserId)
                .orElseGet(() -> new TelegramBotUserEntity(telegramUserId, displayName));
        user.update(displayName, true);
        repository.save(user);
    }

    @Transactional
    public void setActive(long telegramUserId, boolean active) {
        TelegramBotUserEntity user = repository.findById(telegramUserId)
                .orElseThrow(() -> new IllegalArgumentException("Telegram user ID is not registered."));
        user.update(null, active);
        repository.save(user);
    }
}
