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

    public boolean send(Long chatId, String text) {
        try {
            telegram.execute(SendMessage.builder().chatId(chatId).text(text).build());
            return true;
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message ({})", rootCauseType(e));
            return false;
        }
    }

    private static String rootCauseType(Throwable failure) {
        Throwable rootCause = failure;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getSimpleName();
    }
}
