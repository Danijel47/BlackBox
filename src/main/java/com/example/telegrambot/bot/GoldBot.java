package com.example.telegrambot.bot;

import com.example.telegrambot.service.NjuskaloMonitorService;
import com.example.telegrambot.service.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
@Slf4j
public class GoldBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final String SOURCE = "GOLD_XAU";

    private final String token;
    private final TelegramClient client;
    private final SubscriptionService subscriptionService;
    private final NjuskaloMonitorService njuskaloMonitorService;

    public GoldBot(@Value("${telegram.gold.bot.token}") String token, @Qualifier("goldClient") TelegramClient client, SubscriptionService subscriptionService, NjuskaloMonitorService njuskaloMonitorService) {
        this.token = token;
        this.client = client;
        this.subscriptionService = subscriptionService;
        this.njuskaloMonitorService = njuskaloMonitorService;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {

        log.info("RAW UPDATE: {}", update);

        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();

        String cmd = text.split("\\s+")[0];

        int at = cmd.indexOf('@');
        if (at != -1) cmd = cmd.substring(0, at);

        switch (cmd) {
            case "/nj" -> {
                njuskaloMonitorService.checkOnceForChat(chatId);
                send(chatId, "Njuskalo check triggered.");
                return;
            }
            case "/njstart" -> {
                boolean created = subscriptionService.subscribe(chatId, NjuskaloMonitorService.SOURCE);
                if (created) {
                    int primed = njuskaloMonitorService.prime();
                    send(chatId, "Njuškalo alerts enabled. Primed " + primed +
                                 " existing ads. I’ll notify you hourly about new ones.");
                } else {
                    send(chatId, "You’re already subscribed. Use /njstop to unsubscribe.");
                }
                return;
            }
            case "/njstop" -> {
                subscriptionService.unsubscribe(chatId, NjuskaloMonitorService.SOURCE);
                send(chatId, "Njuškalo alerts disabled.");
                return;
            }
            case "/start" -> {
                boolean created = subscriptionService.subscribe(chatId, SOURCE);
                send(chatId, created
                        ? "Subscribed to gold updates (XAU). Use /stop to unsubscribe."
                        : "You’re already subscribed. Use /stop to unsubscribe.");
                return;
            }
            case "/stop" -> {
                subscriptionService.unsubscribe(chatId, SOURCE);
                send(chatId, "Unsubscribed.");
                return;
            }
            default -> send(chatId, "Unknown command: " + cmd);
        }

        send(chatId, "Commands: /start, /stop");
    }

    private void send(long chatId, String msg) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).text(msg).build());
        } catch (Exception e) {
            log.error("SEND failed to chatId={}: {}", chatId, e.getMessage(), e);
        }
    }
}

