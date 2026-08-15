package com.example.blackbox.wow.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class TelegramClientConfig {

    @Bean("rioClient")
    TelegramClient rioClient(@Value("${telegram.rio.bot.token}") String token) {
        return new OkHttpTelegramClient(token);
    }

    @Bean("telegramHealthClient")
    TelegramClient telegramHealthClient(@Value("${telegram.rio.bot.token}") String token) {
        OkHttpClient healthHttpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .build();
        return new OkHttpTelegramClient(healthHttpClient, token);
    }
}
