package com.example.blackbox.wow.bot;

import com.example.blackbox.time_to_go.service.TimeToGoCommandService;
import com.example.blackbox.wow.client.RaiderIoClient;
import com.example.blackbox.wow.helper.AffixFormatter;
import com.example.blackbox.wow.helper.RaidPicker;
import com.example.blackbox.wow.helper.RaidProgressFormatter;
import com.example.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.example.blackbox.wow.properties.WowWatchlistProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.blackbox.wow.blizzard.BlizzardAuctionService;
import com.example.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.example.blackbox.wow.blizzard.BlizzardItemService;
import com.example.blackbox.wow.blizzard.BlizzardItemService.ItemRef;
import com.example.blackbox.wow.blizzard.BlizzardMountService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class RioBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final int INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS = 600;
    private static final Duration TITLE_WATCH_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TITLE_PREDICTION_CACHE_TTL = Duration.ofMinutes(30);
    private static final String TITLE_CUTOFF_REGION = "eu";
    private static final TitleWatchPlayer TITLE_01_PLAYER = new TitleWatchPlayer("eu", "Tarren Mill", "Polivé");
    private static final List<TitleWatchPlayer> TITLE_WATCH_PLAYERS = List.of(
            new TitleWatchPlayer("eu", "stormscale", "bucothered"),
            new TitleWatchPlayer("eu", "stormscale", "lazozero"),
            new TitleWatchPlayer("eu", "stormscale", "linqq"),
            new TitleWatchPlayer("eu", "Darksorrow", "Felmm"),
            new TitleWatchPlayer("eu", "stormscale", "Zsmodk"),
            new TitleWatchPlayer("eu", "Tarren Mill", "Polivé")
    );
    private static final Map<String, List<Long>> MIDNIGHT_MATERIAL_IDS = Map.ofEntries(
            Map.entry("refulgent copper ore", List.of(237359L, 237361L)),
            Map.entry("umbral tin ore", List.of(237362L, 237363L)),
            Map.entry("brilliant silver ore", List.of(237364L, 237365L)),
            Map.entry("dazzling thorium", List.of(237366L)),
            Map.entry("dazzling thorium ore", List.of(237366L)),
            Map.entry("tranquility bloom", List.of(236761L, 236767L)),
            Map.entry("sanguithorn", List.of(236770L, 236771L)),
            Map.entry("azeroot", List.of(236774L, 236775L)),
            Map.entry("argentleaf", List.of(236776L, 236777L)),
            Map.entry("mana lily", List.of(236778L, 236779L)),
            Map.entry("nocturnal lotus", List.of(236780L))
    );

    private final String token;
    private final TelegramClient client;
    private final RaiderIoClient raiderIoClient;
    private final RaiderIoDefaultGuildProperties defaultGuildProps;
    private final WowWatchlistProperties watchlistProps;
    private final BlizzardAuctionService auctionService;
    private final BlizzardItemService itemService;
    private final BlizzardMountService mountService;
    private final TimeToGoCommandService timeToGoCommands;
    private TitleWatchCache titleWatchCache;
    private TitleWatchCache title01WatchCache;
    private TitlePredictionCache titlePredictionCache;
    private TitlePredictionCache title01PredictionCache;

    public RioBot(
            @Value("${telegram.rio.bot.token}") String token,
            @Qualifier("rioClient") TelegramClient client,
            RaiderIoClient raiderIoClient,
            RaiderIoDefaultGuildProperties defaultGuildProps,
            WowWatchlistProperties watchlistProps,
            BlizzardAuctionService auctionService,
            BlizzardItemService itemService,
            BlizzardMountService mountService,
            TimeToGoCommandService timeToGoCommands
    ) {
        this.token = token;
        this.client = client;
        this.raiderIoClient = raiderIoClient;
        this.defaultGuildProps = defaultGuildProps;
        this.watchlistProps = watchlistProps;
        this.auctionService = auctionService;
        this.itemService = itemService;
        this.mountService = mountService;
        this.timeToGoCommands = timeToGoCommands;
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

        String cmd = text.split("\\s+")[0];
        int at = cmd.indexOf('@');
        if (at != -1) cmd = cmd.substring(0, at);

        if (cmd.equals("/affixes")) {
            JsonNode data = raiderIoClient.getWeeklyAffixes("eu", "en");
            send(chatId, AffixFormatter.formatWeeklyAffixes(data));
            return;
        }

        if (cmd.equals("/guild")) {
            String[] parts = text.split("\\s+", 2);
            String arg = parts.length > 1 ? parts[1].trim() : "";

            var p = defaultGuildProps;
            JsonNode g = raiderIoClient.getGuildProfile(p.region(), p.realm(), p.guildName());

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


        if (cmd.equals("/price")) {
            String args = text.length() > cmd.length() ? text.substring(cmd.length()).trim() : "";
            if (args.isBlank()) {
                send(chatId, "Usage: /price <itemId|item name> [realm-if-itemId]\nExamples:\n/price 72092 Draenor\n/price wow token");
                return;
            }

            try {
                String[] firstAndRest = args.split("\\s+", 2);
                Long numericId = parseLong(firstAndRest[0]);

                if (numericId != null) {
                    String realm = firstAndRest.length >= 2 ? firstAndRest[1].trim() : "";
                    ItemRef item = itemService.getById(numericId);
                    String itemName = resolveDisplayName(item, numericId);

                    if (realm.isBlank()) {
                        PriceResult result = auctionService.getRegionAverage(numericId);
                        if (!result.available() && isWowToken(itemName)) {
                            result = auctionService.getWowTokenPrice();
                            send(chatId, formatPriceMessage("WoW Token (EU)", itemName, result));
                            return;
                        }
                        send(chatId, formatPriceMessage("Region avg (EU)", itemName, result));
                    } else {
                        PriceResult result = auctionService.getRealmAverage(realm, numericId);
                        send(chatId, formatPriceMessage("Realm " + realm, itemName, result));
                    }
                } else {
                    ItemRef item = itemService.findByName(args);
                    if (item == null) {
                        send(chatId, "Item not found: " + args);
                        return;
                    }

                    if (isWowToken(item.name())) {
                        PriceResult result = auctionService.getWowTokenPrice();
                        send(chatId, formatPriceMessage("WoW Token (EU)", item.name(), result));
                        return;
                    }

                    PriceResult result = auctionService.getRegionAverage(item.id());
                    send(chatId, formatPriceMessage("Region avg (EU)", item.name(), result));
                }
            } catch (Exception e) {
                send(chatId, "Blizzard price lookup failed: " + e.getMessage());
            }
            return;
        }

        if (cmd.equals("/priceah")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 3) {
                send(chatId, "Usage: /priceah <connectedRealmId> <auctionHouseId> <itemId>\nExample: /priceah 1080 2 72092");
                return;
            }

            if (parts.length < 4) {
                send(chatId, "Usage: /priceah <connectedRealmId> <auctionHouseId> <itemId>\nExample: /priceah 1080 2 72092");
                return;
            }

            Long connectedRealmId = parseLong(parts[1]);
            Long auctionHouseId = parseLong(parts[2]);
            Long itemId = parseLong(parts[3]);
            if (connectedRealmId == null || auctionHouseId == null || itemId == null) {
                send(chatId, "Invalid numbers. Example: /priceah 1080 2 72092");
                return;
            }

            try {
                PriceResult result = auctionService.getAuctionHouseAverage(connectedRealmId, auctionHouseId, itemId);
                ItemRef item = itemService.getById(itemId);
                send(chatId, formatPriceMessage("AuctionHouse " + auctionHouseId, resolveDisplayName(item, itemId), result));
            } catch (Exception e) {
                send(chatId, "Blizzard price lookup failed: " + e.getMessage());
            }
            return;
        }

        if (cmd.equals("/token")) {
            try {
                PriceResult result = auctionService.getWowTokenPrice();
                send(chatId, formatPriceMessage("WoW Token (EU)", "WoW Token", result));
            } catch (Exception e) {
                send(chatId, "Blizzard token lookup failed: " + e.getMessage());
            }
            return;
        }

        if (cmd.equals("/mount-achiv")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 3) {
                send(chatId, "Usage: /mount-achiv <realm> <name>\nExample: /mount-achiv stormscale bucothered");
                return;
            }

            try {
                var progress = mountService.getMountProgress(parts[1], parts[2]);
                send(chatId, "Insurmountable Collection: " + formatMountAchievementProgress(progress.usable()));
            } catch (Exception e) {
                send(chatId, formatMountLookupError(parts[1], parts[2], e));
            }
            return;
        }

        if (cmd.equals("/ores") || cmd.equals("/ore")) {
            send(chatId, formatWatchlistWithSilverGold("Ores (EU)", watchlistProps.ores()));
            return;
        }

        if (cmd.equals("/herbs") || cmd.equals("/herb")) {
            send(chatId, formatWatchlistWithSilverGold("Herbs (EU)", watchlistProps.herbs()));
            return;
        }

        if (cmd.equals("/title") || cmd.equals("/titlewatch")) {
            send(chatId, formatTitleWatch());
            return;
        }

        if (cmd.equals("/title01") || cmd.equals("/title0.1") || cmd.equals("/title001")) {
            send(chatId, formatTitle01Watch());
            return;
        }

        if (cmd.equals("/road") || cmd.equals("/travel") || cmd.equals("/timetogo")) {
            send(chatId, timeToGoCommands.formatCurrent(text));
            return;
        }

        if (cmd.equals("/roadbest") || cmd.equals("/travelbest") || cmd.equals("/timetogobest")) {
            send(chatId, timeToGoCommands.formatBest(text));
            return;
        }

        if (cmd.equals("/timetogoimport30") || cmd.equals("/roadimport30") || cmd.equals("/travelimport30")) {
            try {
                send(chatId, timeToGoCommands.submitHistoricalImport());
            } catch (Exception e) {
                send(chatId, "TomTom historical import submit failed: " + e.getMessage());
            }
            return;
        }

        if (cmd.equals("/timetogoimportstatus") || cmd.equals("/roadimportstatus") || cmd.equals("/travelimportstatus")) {
            try {
                send(chatId, timeToGoCommands.refreshHistoricalImport());
            } catch (Exception e) {
                send(chatId, "TomTom historical import status failed: " + e.getMessage());
            }
            return;
        }

        if (cmd.equals("/vault")) {
            String[] parts = text.split("\\s+");
            if (parts.length == 1) {
                send(chatId, formatWeeklyVaultWatch());
                return;
            }
            if (parts.length != 3 && parts.length != 4) {
                send(chatId, "Usage: /vault\nOptional single lookup: /vault <realm> <name>");
                return;
            }

            String region = parts.length >= 4 ? parts[1].toLowerCase() : "eu";
            String realm = parts.length >= 4 ? parts[2] : parts[1];
            String name = parts.length >= 4 ? parts[3] : parts[2];

            try {
                var progress = raiderIoClient.getWeeklyVaultProgress(region, realm, name);
                send(chatId, formatWeeklyVault(progress));
            } catch (Exception e) {
                send(chatId, "Couldn’t fetch weekly vault for " + name + " on " + realm + " (" + region + ").\n" +
                             "Use: /vault <realm> <name>\n" +
                             "Example: /vault stormscale bucothered");
            }
            return;
        }

        if (checkRio(cmd, text, chatId)) return;

        // Optional help
        if (cmd.equals("/help")) {
            send(chatId, "Commands:\n/rio <region> <realm> <name>\n/vault\n/title\n/title01\n/road [zadar zagreb|zagreb zadar]\n/roadbest [zadar zagreb|zagreb zadar]\n/timetogoimport30\n/timetogoimportstatus\n/mount-achiv <realm> <name>\n/price <itemId|item name> [realm-if-itemId]\n/priceah <connectedRealmId> <auctionHouseId> <itemId>\n/token\n/ores\n/herbs");
        }
    }

    private String formatWeeklyVaultWatch() {
        if (TITLE_WATCH_PLAYERS.isEmpty()) {
            return "No vault watch players configured.";
        }

        StringBuilder sb = new StringBuilder("Great Vault M+ watch\n");
        for (TitleWatchPlayer player : TITLE_WATCH_PLAYERS) {
            try {
                var progress = raiderIoClient.getWeeklyVaultProgress(player.region(), player.realm(), player.name());
                sb.append("• ")
                        .append(progress.name())
                        .append(": ")
                        .append(formatVaultSlotSummary(progress.runs()))
                        .append("\n");
            } catch (Exception e) {
                sb.append("• ")
                        .append(player.name())
                        .append(": error")
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String formatWeeklyVault(RaiderIoClient.WeeklyVaultProgress progress) {
        List<RaiderIoClient.MPlusRun> runs = progress.runs() == null ? List.of() : progress.runs();

        StringBuilder sb = new StringBuilder("Great Vault M+\n");
        sb.append(progress.name()).append(" - ").append(progress.realm()).append(" (").append(progress.region()).append(")\n");
        sb.append("Top weekly runs from Raider.IO: ").append(runs.size()).append("\n");
        sb.append("Slot 1 (1 run): ").append(formatVaultSlot(runs, 1)).append("\n");
        sb.append("Slot 2 (4 runs): ").append(formatVaultSlot(runs, 4)).append("\n");
        sb.append("Slot 3 (8 runs): ").append(formatVaultSlot(runs, 8)).append("\n");

        if (runs.isEmpty()) {
            sb.append("Top runs: none found for the current reset");
        } else {
            sb.append("Top runs:\n");
            for (int i = 0; i < Math.min(runs.size(), 8); i++) {
                RaiderIoClient.MPlusRun run = runs.get(i);
                sb.append(i + 1)
                        .append(". +").append(run.level())
                        .append(" ").append(run.dungeon())
                        .append("\n");
            }
        }

        if (progress.profileUrl() != null && !progress.profileUrl().isBlank()) {
            sb.append("\nProfile: ").append(progress.profileUrl());
        }
        return sb.toString().trim();
    }

    private static String formatVaultSlotSummary(List<RaiderIoClient.MPlusRun> runs) {
        List<RaiderIoClient.MPlusRun> safeRuns = runs == null ? List.of() : runs;
        if (safeRuns.isEmpty()) {
            return "no current-reset runs found";
        }

        return "top " + safeRuns.size()
               + " | 1: " + formatVaultSlot(safeRuns, 1)
               + " | 4: " + formatVaultSlot(safeRuns, 4)
               + " | 8: " + formatVaultSlot(safeRuns, 8);
    }

    private static String formatVaultSlot(List<RaiderIoClient.MPlusRun> runs, int requiredRuns) {
        if (runs.size() < requiredRuns) {
            return "locked (" + (requiredRuns - runs.size()) + " more)";
        }
        return "+" + runs.get(requiredRuns - 1).level();
    }

    private String formatTitle01Watch() {
        Instant now = Instant.now();
        if (title01WatchCache != null && title01WatchCache.expiresAt().isAfter(now)) {
            return title01WatchCache.message();
        }

        var cutoff = raiderIoClient.getCurrentMPlusTitleCutoff(TITLE_CUTOFF_REGION, "p999");
        BigDecimal cutoffScore = cutoff.score();
        StringBuilder sb = new StringBuilder("M+ 0.1% title watch\n");
        sb.append("Cutoff: ").append(formatScore(cutoffScore))
                .append("\nCutoff updated: ").append(formatCutoffUpdatedAt(cutoff.updatedAt()))
                .append("\n");
        appendTitlePrediction(sb, "p999");

        try {
            var score = raiderIoClient.getCurrentMPlusScore(TITLE_01_PLAYER.region(), TITLE_01_PLAYER.realm(), TITLE_01_PLAYER.name());
            BigDecimal all = score.all();
            BigDecimal remaining = all == null ? null : cutoffScore.subtract(all).max(BigDecimal.ZERO);
            BigDecimal above = all == null ? null : all.subtract(cutoffScore).max(BigDecimal.ZERO);
            sb.append("• ").append(score.name()).append(": ").append(formatTitleScoreLine(all, remaining, above));
        } catch (Exception e) {
            sb.append("• ").append(TITLE_01_PLAYER.name()).append(": error: ").append(e.getMessage());
        }

        String message = sb.toString().trim();
        title01WatchCache = new TitleWatchCache(message, now.plus(TITLE_WATCH_CACHE_TTL));
        return message;
    }

    private String formatTitleWatch() {
        if (TITLE_WATCH_PLAYERS.isEmpty()) {
            return "No title watch players configured.";
        }

        Instant now = Instant.now();
        if (titleWatchCache != null && titleWatchCache.expiresAt().isAfter(now)) {
            return titleWatchCache.message();
        }

        var cutoff = raiderIoClient.getCurrentMPlusTitleCutoff(TITLE_CUTOFF_REGION);
        BigDecimal cutoffScore = cutoff.score();

        List<TitleWatchResult> results = new ArrayList<>();
        for (TitleWatchPlayer player : TITLE_WATCH_PLAYERS) {
            try {
                var score = raiderIoClient.getCurrentMPlusScore(player.region(), player.realm(), player.name());
                BigDecimal all = score.all();
                BigDecimal remaining = all == null ? null : cutoffScore.subtract(all).max(BigDecimal.ZERO);
                BigDecimal above = all == null ? null : all.subtract(cutoffScore).max(BigDecimal.ZERO);
                results.add(new TitleWatchResult(score.name(), score.realm(), score.region(), all, remaining, above, null));
            } catch (Exception e) {
                results.add(new TitleWatchResult(player.name(), player.realm(), player.region(), null, null, null, e.getMessage()));
            }
        }

        results.sort(Comparator.comparing(
                TitleWatchResult::score,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        StringBuilder sb = new StringBuilder("M+ 1% title watch\n");
        sb.append("Cutoff: ").append(formatScore(cutoffScore))
                .append("\nCutoff updated: ").append(formatCutoffUpdatedAt(cutoff.updatedAt()))
                .append("\n");
        appendTitlePrediction(sb, "p990");
        for (TitleWatchResult result : results) {
            sb.append("• ")
                    .append(result.name()).append(": ");
            if (result.error() != null) {
                sb.append("error: ").append(result.error()).append("\n");
            } else if (result.score() == null) {
                sb.append("n/a\n");
            } else if (result.remaining() == null) {
                sb.append(formatScore(result.score())).append(" | remaining n/a\n");
            } else if (result.remaining().compareTo(BigDecimal.ZERO) == 0) {
                sb.append(formatScore(result.score()))
                        .append(" | above by ").append(formatScore(result.above()))
                        .append("\n");
            } else {
                sb.append(formatScore(result.score()))
                        .append(" | remaining ").append(formatScore(result.remaining()))
                        .append("\n");
            }
        }

        String message = sb.toString().trim();
        titleWatchCache = new TitleWatchCache(message, now.plus(TITLE_WATCH_CACHE_TTL));
        return message;
    }

    private void appendTitlePrediction(StringBuilder sb, String percentileKey) {
        var prediction = getCachedTitlePrediction(percentileKey);
        if (prediction == null) {
            sb.append("Predicted season end: n/a\n");
            return;
        }

        sb.append("Predicted season end: ")
                .append(formatScore(prediction.predictedScore()))
                .append(" (").append(formatPredictionFor(prediction.predictionFor())).append(")")
                .append("\n");
    }

    private RaiderIoClient.MPlusTitlePrediction getCachedTitlePrediction(String percentileKey) {
        Instant now = Instant.now();
        TitlePredictionCache cache = "p999".equals(percentileKey) ? title01PredictionCache : titlePredictionCache;
        if (cache != null && cache.expiresAt().isAfter(now)) {
            return cache.prediction();
        }

        try {
            var prediction = raiderIoClient.getCurrentMPlusTitlePrediction(TITLE_CUTOFF_REGION, percentileKey);
            var nextCache = new TitlePredictionCache(prediction, now.plus(TITLE_PREDICTION_CACHE_TTL));
            if ("p999".equals(percentileKey)) {
                title01PredictionCache = nextCache;
            } else {
                titlePredictionCache = nextCache;
            }
            return prediction;
        } catch (Exception e) {
            return cache == null ? null : cache.prediction();
        }
    }

    private static String formatTitleScoreLine(BigDecimal score, BigDecimal remaining, BigDecimal above) {
        if (score == null) {
            return "n/a";
        }
        if (remaining == null) {
            return formatScore(score) + " | remaining n/a";
        }
        if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            return formatScore(score) + " | above by " + formatScore(above);
        }
        return formatScore(score) + " | remaining " + formatScore(remaining);
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
        } catch (Exception ignored) {
        }
    }

    private static String formatMountLookupError(String realm, String name, Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("404")) {
            return "Mount progression not found for " + name + " on " + realm + " (EU).\n" +
                   "Use: /mount-achiv <realm> <name>\n" +
                   "Example: /mount-achiv stormscale bucothered\n" +
                   "Also check that the character exists on EU and has logged out recently.";
        }

        return "Couldn’t fetch mount progression for " + name + " on " + realm + " (EU).\n" +
               "Try again later, or check the realm and character name.";
    }

    private static String formatMountAchievementProgress(int usableMounts) {
        int missing = Math.max(0, INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS - usableMounts);
        if (missing == 0) {
            return usableMounts + "/" + INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS + " completed";
        }
        return usableMounts + "/" + INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS + " (" + missing + " missing)";
    }

    private static String formatLastCrawled(String isoUtc) {
        if (isoUtc == null || isoUtc.isBlank() || isoUtc.equals("n/a")) return "n/a";
        try {
            Instant i = Instant.parse(isoUtc);
            ZonedDateTime zagreb = i.atZone(ZoneId.of("Europe/Zagreb"));
            return zagreb.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return "n/a";
        }
    }

    private static String formatCutoffUpdatedAt(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) return "n/a";
        try {
            ZonedDateTime utc = ZonedDateTime.parse(
                    updatedAt,
                    DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z '('zzzz')'", java.util.Locale.ENGLISH)
            );
            return utc.withZoneSameInstant(ZoneId.of("Europe/Zagreb"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        } catch (Exception e) {
            return updatedAt;
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatPriceMessage(String scope, String itemName, PriceResult result) {
        if (!result.available()) {
            return "No pricing data for " + itemName + " (" + scope + ").";
        }
        return scope + " avg for " + itemName + ": " + formatCopper(result.avgCopper());
    }

    private static String resolveDisplayName(ItemRef item, long fallbackItemId) {
        if (item != null && item.name() != null && !item.name().isBlank()) {
            return item.name();
        }
        return "item " + fallbackItemId;
    }

    private static boolean isWowToken(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase();
        return normalized.equals("wow token");
    }

    private static String formatScore(BigDecimal score) {
        if (score == null) return "n/a";
        return score.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatPredictionFor(Instant predictionFor) {
        if (predictionFor == null) return "n/a";
        return predictionFor.atZone(ZoneId.of("Europe/Zagreb"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private String formatWatchlistWithSilverGold(String title, List<String> names) {
        if (names == null || names.isEmpty()) {
            return title + "\nNo items configured.";
        }

        StringBuilder sb = new StringBuilder(title).append("\n");
        for (String rawBase : names) {
            String baseName = rawBase == null ? "" : rawBase.trim();
            if (baseName.isBlank()) continue;

            try {
                List<ItemRef> ranks = findMaterialRanks(baseName);

                if (ranks.size() == 1) {
                    sb.append("• ").append(baseName)
                            .append(": ").append(formatItemPriceOrState(ranks.getFirst(), "n/a"))
                            .append("\n");
                } else {
                    ItemRef silver = findMaterialRank(ranks, 2);
                    ItemRef gold = findMaterialRank(ranks, 3);

                    String silverPrice = formatItemPriceOrState(silver, "n/a");
                    String goldPrice = formatItemPriceOrState(gold, "n/a");

                    sb.append("• ").append(baseName)
                            .append(" | S: ").append(silverPrice)
                            .append(" | G: ").append(goldPrice)
                            .append("\n");
                }
            } catch (Exception e) {
                sb.append("• ").append(baseName).append(": error").append("\n");
            }
        }
        return sb.toString().trim();
    }

    private List<ItemRef> findMaterialRanks(String baseName) {
        List<Long> ids = MIDNIGHT_MATERIAL_IDS.get(baseName.toLowerCase());
        if (ids == null) {
            return itemService.findExactByName(baseName);
        }
        return ids.stream()
                .map(id -> new ItemRef(id, baseName))
                .toList();
    }

    private static ItemRef findMaterialRank(List<ItemRef> ranks, int qualityRank) {
        if (ranks == null || ranks.isEmpty()) return null;
        if (qualityRank == 2) {
            return ranks.size() >= 3 ? ranks.get(1) : ranks.get(0);
        }
        if (qualityRank == 3) {
            return ranks.size() >= 3 ? ranks.get(2) : (ranks.size() >= 2 ? ranks.get(1) : null);
        }
        return null;
    }

    private String formatItemPriceOrState(ItemRef item, String emptyLabel) {
        if (item == null) return emptyLabel;
        PriceResult result = isWowToken(item.name())
                ? auctionService.getWowTokenPrice()
                : auctionService.getRegionAverage(item.id());
        return result.available() ? formatCopper(result.avgCopper()) : emptyLabel;
    }

    private static String formatCopper(long copper) {
        long gold = copper / 10_000;
        long silver = (copper % 10_000) / 100;
        return gold + "g " + silver + "s";
    }

    private record TitleWatchPlayer(String region, String realm, String name) {
    }

    private record TitleWatchResult(
            String name,
            String realm,
            String region,
            BigDecimal score,
            BigDecimal remaining,
            BigDecimal above,
            String error
    ) {
    }

    private record TitleWatchCache(String message, Instant expiresAt) {
    }

    private record TitlePredictionCache(RaiderIoClient.MPlusTitlePrediction prediction, Instant expiresAt) {
    }

}
