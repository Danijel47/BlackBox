package com.example.blackbox.wow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Service
@Slf4j
public class RioBotNotifier {

    private final TelegramClient telegram;

    public RioBotNotifier(@Qualifier("rioClient") TelegramClient telegram) {
        this.telegram = telegram;
    }

    public void send(Long chatId, String text) {
        try {
            telegram.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chatId={}: {}", chatId, e.getMessage(), e);
        }
    }
}
