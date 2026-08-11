package com.example.blackbox.wow.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TelegramAccessPolicy {

    private final long adminUserId;
    private final Set<Long> allowedChatIds;
    private final TelegramBotUserService telegramBotUserService;
    private final Cache<Long, Boolean> allowedUserCache;

    public TelegramAccessPolicy(
            @Value("${telegram.admin-user-id:0}") long adminUserId,
            @Value("${telegram.allowed-chat-ids:}") String allowedChatIds,
            TelegramBotUserService telegramBotUserService
    ) {
        this.adminUserId = adminUserId;
        this.allowedChatIds = parseChatIds(allowedChatIds);
        this.telegramBotUserService = telegramBotUserService;
        this.allowedUserCache = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    public boolean isAllowed(long chatId, Long senderUserId) {
        if (adminUserId > 0 && senderUserId != null && senderUserId == adminUserId) {
            return true;
        }
        if (senderUserId == null) {
            return false;
        }
        boolean allowedUser = allowedUserCache.get(
                senderUserId,
                telegramBotUserService::isActive
        );
        return allowedUser && (allowedChatIds.isEmpty() || allowedChatIds.contains(chatId));
    }

    public void userAccessChanged(long telegramUserId) {
        allowedUserCache.invalidate(telegramUserId);
    }

    private static Set<Long> parseChatIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isBlank())
                    .map(Long::parseLong)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "TELEGRAM_ALLOWED_CHAT_IDS must contain comma-separated numeric chat IDs.",
                    e
            );
        }
    }
}
