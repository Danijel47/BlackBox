package com.example.telegrambot.bot;

import com.example.telegrambot.clinet.RaiderIoClient;
import com.example.telegrambot.helper.AffixFormatter;
import com.example.telegrambot.helper.RaidPicker;
import com.example.telegrambot.helper.RaidProgressFormatter;
import com.example.telegrambot.properties.RaiderIoDefaultGuildProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RioBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final String token;
    private final TelegramClient client;
    private final RaiderIoClient raiderIoClient;
    private final RaiderIoDefaultGuildProperties defaultGuildProps;

    public RioBot(
            @Value("${telegram.rio.bot.token}") String token,
            @Qualifier("rioClient") TelegramClient client,
            RaiderIoClient raiderIoClient, RaiderIoDefaultGuildProperties defaultGuildProps
    ) {
        this.token = token;
        this.client = client;
        this.raiderIoClient = raiderIoClient;
        this.defaultGuildProps = defaultGuildProps;
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
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) return;

        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();

        // Robust command parsing for groups: "/rio@BotName ..."
        String cmd = text.split("\\s+")[0];
        int at = cmd.indexOf('@');
        if (at != -1) cmd = cmd.substring(0, at);

        if (text.equals("/affixes")) {
            JsonNode data = raiderIoClient.getWeeklyAffixes("eu", "en");
            send(chatId, AffixFormatter.formatWeeklyAffixes(data));
            return;
        }

        if (cmd.equals("/guild")) {
            String[] parts = text.split("\\s+", 2);
            String arg = parts.length > 1 ? parts[1].trim() : "";

            JsonNode g = raiderIoClient.getGuildProfile("eu", "draenor", "B U R A Z Z E R S");

            String raidKey;
            if (!arg.isBlank() && !arg.equalsIgnoreCase("list")) {
                raidKey = arg; // user chooses: /guild nerubar-palace
            } else if (defaultGuildProps.raidName() != null && !defaultGuildProps.raidName().isBlank()) {
                raidKey = defaultGuildProps.raidName(); // property chooses
            } else {
                raidKey = RaidPicker.pickBestRaidKey(g); // auto
            }

            String last = g.path("last_crawled_at").asText("n/a");

            if (arg.equalsIgnoreCase("list")) {
                StringBuilder sb = new StringBuilder("Raids:\n");
                g.path("raid_progression").fieldNames().forEachRemaining(k -> sb.append("• ").append(RaidProgressFormatter.formatRaidLine(g, k)).append("\n"));
                sb.append("\nUse: /guild <raidKey>");
                send(chatId, sb.toString());
                return;
            }

            send(chatId,
                    defaultGuildProps.guildName() + "\n" +
                    RaidProgressFormatter.formatRaidLine(g, raidKey) + "\nLast update: " + formatLastCrawled(last)
            );
            return;
        }


        if (cmd.equals("/guildlist")) {
            var p = defaultGuildProps;
            JsonNode g = raiderIoClient.getGuildProfile(p.region(), p.realm(), p.guildName());

            StringBuilder sb = new StringBuilder("Available raids:\n");
            g.path("raid_progression").fieldNames().forEachRemaining(k -> sb.append("• ").append(k).append("\n"));

            send(chatId, sb.toString());
            return;
        }


        if (checkRio(cmd, text, chatId)) return;

        // Optional help
        if (cmd.equals("/help")) {
            send(chatId, "Commands:\n/rio <region> <realm> <name>");
        }
    }

    private boolean checkRio(String cmd, String text, long chatId) {
        if (cmd.equals("/rio")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 4) {
                send(chatId, "Usage: /rio <region> <realm> <name>\nExample: /rio eu stormscale bucothered");
                return true;
            }

            String region = parts[1].toLowerCase();
            String realm = parts[2];
            String name = parts[3];

            try {
                var s = raiderIoClient.getCurrentMPlusScore(region, realm, name);

                String msg =
                        "Raider.IO (current season)\n" +
                        s.name() + " - " + s.realm() + " (" + s.region() + ")\n" +
                        "Score: " + (s.all() == null ? "n/a" : s.all()) + "\n" +
                        "DPS: " + (s.dps() == null ? "n/a" : s.dps()) +
                        " | Healer: " + (s.healer() == null ? "n/a" : s.healer()) +
                        " | Tank: " + (s.tank() == null ? "n/a" : s.tank()) +
                        (s.profileUrl() == null || s.profileUrl().isBlank() ? "" : ("\nProfile: " + s.profileUrl()));

                send(chatId, msg);
            } catch (Exception e) {
                send(chatId, "Couldn’t fetch Raider.IO for " + region + "/" + realm + "/" + name +
                             "\nReason: " + e.getMessage());
            }
            return true;
        }
        return false;
    }

    private void send(long chatId, String msg) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).text(msg).build());
        } catch (Exception _) {
        }
    }

    private static String formatLastCrawled(String isoUtc) {
        if (isoUtc == null || isoUtc.isBlank() || isoUtc.equals("n/a")) return "n/a";
        Instant i = Instant.parse(isoUtc);

        ZonedDateTime zagreb = i.atZone(ZoneId.of("Europe/Zagreb"));
        return zagreb.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

}
