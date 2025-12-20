package com.example.telegrambot.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Service
public class GoldBotNotifier {

    private final TelegramClient telegram;

    public GoldBotNotifier(@Qualifier("goldClient") TelegramClient telegram) {
        this.telegram = telegram;
    }

    public void send(Long chatId, String text) {
        try {
            telegram.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            // replace with proper logger
            e.printStackTrace();
        }
    }
}
