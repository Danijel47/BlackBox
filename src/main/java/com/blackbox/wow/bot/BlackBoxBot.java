package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.helper.AffixFormatter;
import com.blackbox.wow.helper.RaidPicker;
import com.blackbox.wow.helper.RaidProgressFormatter;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.RaiderIoAbandonedRunService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.TelegramAccessPolicy;
import com.blackbox.wow.service.TelegramBotUserService;
import com.blackbox.wow.service.VaultReminderService;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService.PlayerStatistics;
import com.fasterxml.jackson.databind.JsonNode;
import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardItemService.ItemRef;
import com.blackbox.wow.blizzard.BlizzardMountService;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToIntFunction;

@Component
public class BlackBoxBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final int INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS = 600;
    private static final Duration TITLE_WATCH_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TITLE_PREDICTION_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration SEASON_RECAP_CACHE_TTL = Duration.ofHours(24);
    private static final Duration FAILED_SEASON_RECAP_CACHE_TTL = Duration.ofMinutes(10);
    private static final List<String> PEON_WORK_MESSAGES = List.of(
            "Work, work... fetching the data. 🛠️",
            "Zug zug! The peon is checking. 🔎",
            "Something need doing? Still working on it. ⛏️",
            "Back to work! Your result is being prepared. 🧱"
    );
    private static final String MIDNIGHT_SEASON_ONE = "season-mn-1";
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
    private final long adminUserId;
    private final TelegramClient client;
    private final RaiderIoClient raiderIoClient;
    private final RaiderIoDefaultGuildProperties defaultGuildProps;
    private final WowWatchlistProperties watchlistProps;
    private final BlizzardAuctionService auctionService;
    private final BlizzardItemService itemService;
    private final BlizzardMountService mountService;
    private final TimeToGoCommandService timeToGoCommands;
    private final RaiderIoAbandonedRunService abandonedRunService;
    private final TrackedPlayerService trackedPlayerService;
    private final VaultReminderService vaultReminderService;
    private final WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    private final TelegramAccessPolicy telegramAccessPolicy;
    private final TelegramBotUserService telegramBotUserService;
    private final List<CommandHandler> commandHandlers;
    private final ScheduledExecutorService workingMessageScheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform()
                    .daemon(true)
                    .name("telegram-working-message")
                    .unstarted(runnable)
    );
    private TitleWatchCache titleWatchCache;
    private TitleWatchCache title01WatchCache;
    private TitlePredictionCache titlePredictionCache;
    private TitlePredictionCache title01PredictionCache;
    private SeasonRunCountsCache seasonRunCountsCache;

    public BlackBoxBot(
            @Value("${telegram.blackbox.bot.token}") String token,
            @Value("${telegram.admin-user-id:0}") long adminUserId,
            @Qualifier("blackBoxTelegramClient") TelegramClient client,
            RaiderIoClient raiderIoClient,
            RaiderIoDefaultGuildProperties defaultGuildProps,
            WowWatchlistProperties watchlistProps,
            BlizzardAuctionService auctionService,
            BlizzardItemService itemService,
            BlizzardMountService mountService,
            TimeToGoCommandService timeToGoCommands,
            RaiderIoAbandonedRunService abandonedRunService,
            TrackedPlayerService trackedPlayerService,
            VaultReminderService vaultReminderService,
            WarcraftLogsStatisticsService warcraftLogsStatisticsService,
            TelegramAccessPolicy telegramAccessPolicy,
            TelegramBotUserService telegramBotUserService
    ) {
        this.token = token;
        this.adminUserId = adminUserId;
        this.client = client;
        this.raiderIoClient = raiderIoClient;
        this.defaultGuildProps = defaultGuildProps;
        this.watchlistProps = watchlistProps;
        this.auctionService = auctionService;
        this.itemService = itemService;
        this.mountService = mountService;
        this.timeToGoCommands = timeToGoCommands;
        this.abandonedRunService = abandonedRunService;
        this.trackedPlayerService = trackedPlayerService;
        this.vaultReminderService = vaultReminderService;
        this.warcraftLogsStatisticsService = warcraftLogsStatisticsService;
        this.telegramAccessPolicy = telegramAccessPolicy;
        this.telegramBotUserService = telegramBotUserService;
        this.commandHandlers = List.of(
                this::handleUserAdministrationCommand,
                this::handleProfileAdministrationCommand,
                this::handleWarcraftInformationCommand,
                this::handleEconomyCommand,
                this::handleSeasonCommand,
                this::handleTravelCommand,
                this::handleGeneralCommand
        );
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
        CommandContext context = CommandContext.from(update);
        if (context == null) {
            return;
        }

        if (context.command().equals("/myid")) {
            send(context.chatId(), formatTelegramIdentity(update));
            return;
        }

        if (!telegramAccessPolicy.isAllowed(context.chatId(), context.senderUserId())) {
            return;
        }

        ScheduledFuture<?> workingMessage = scheduleWorkingMessage(context.chatId());
        try {
            dispatchCommand(context);
        } finally {
            workingMessage.cancel(false);
        }
    }

    private void dispatchCommand(CommandContext context) {
        for (CommandHandler handler : commandHandlers) {
            if (handler.handle(context)) {
                return;
            }
        }
    }

    private boolean handleUserAdministrationCommand(CommandContext context) {
        Update update = context.update();
        long chatId = context.chatId();
        String text = context.text();
        String cmd = context.command();

        if (cmd.equals("/groupid") || cmd.equals("/chatid")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            send(chatId, "Chat ID: " + chatId);
            return true;
        }

        if (cmd.equals("/users") || cmd.equals("/userlist")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            send(chatId, formatTelegramUsers());
            return true;
        }

        if (cmd.equals("/useradd")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            String[] parts = text.split("\\s+", 3);
            if (parts.length < 2) {
                send(chatId, "Usage: /useradd <telegramUserId> [display name]\n"
                        + "Example: /useradd 123456789 Alice");
                return true;
            }
            Long telegramUserId = parseLong(parts[1]);
            if (telegramUserId == null || telegramUserId <= 0) {
                send(chatId, "Telegram user ID must be a positive number.");
                return true;
            }
            String displayName = parts.length == 3 ? parts[2].trim() : null;
            try {
                telegramBotUserService.addOrEnable(telegramUserId, displayName);
                telegramAccessPolicy.userAccessChanged(telegramUserId);
                send(chatId, "Telegram user " + telegramUserId + " is now allowed.");
            } catch (IllegalArgumentException e) {
                send(chatId, "Could not add user: " + e.getMessage());
            }
            return true;
        }

        if (cmd.equals("/userdisable") || cmd.equals("/userenable")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            String[] parts = text.split("\\s+");
            if (parts.length != 2) {
                send(chatId, "Usage: " + cmd + " <telegramUserId>");
                return true;
            }
            Long telegramUserId = parseLong(parts[1]);
            if (telegramUserId == null || telegramUserId <= 0) {
                send(chatId, "Telegram user ID must be a positive number.");
                return true;
            }
            boolean active = cmd.equals("/userenable");
            try {
                telegramBotUserService.setActive(telegramUserId, active);
                telegramAccessPolicy.userAccessChanged(telegramUserId);
                send(chatId, "Telegram user " + telegramUserId
                        + (active ? " enabled." : " disabled."));
            } catch (IllegalArgumentException e) {
                send(chatId, "Could not update user: " + e.getMessage());
            }
            return true;
        }

        return false;
    }

    private boolean handleProfileAdministrationCommand(CommandContext context) {
        Update update = context.update();
        long chatId = context.chatId();
        String text = context.text();
        String cmd = context.command();

        if (cmd.equals("/profiles") || cmd.equals("/profilelist")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            send(chatId, formatPlayerProfiles());
            return true;
        }

        if (cmd.equals("/profileadd")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            String[] parts = text.split("\\s+");
            if (parts.length != 5) {
                send(chatId, "Usage: /profileadd <profile> <region> <realm> <character>\n"
                        + "Example: /profileadd Alice eu stormscale Alicechar");
                return true;
            }
            try {
                trackedPlayerService.addProfile(parts[1], parts[2], parts[3], parts[4]);
                send(chatId, "Profile " + parts[1] + " added with selected character " + parts[4] + ".");
            } catch (IllegalArgumentException e) {
                send(chatId, "Could not add profile: " + e.getMessage());
            }
            return true;
        }

        if (cmd.equals("/profileswitch")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            String[] parts = text.split("\\s+");
            if (parts.length != 5) {
                send(chatId, "Usage: /profileswitch <profile> <region> <realm> <character>\n"
                        + "Example: /profileswitch Alice eu tarren-mill Alicealt");
                return true;
            }
            try {
                trackedPlayerService.switchCharacter(parts[1], parts[2], parts[3], parts[4]);
                send(chatId, "Profile " + parts[1] + " now uses " + parts[4]
                        + ". The previous character was kept as an alt.");
            } catch (IllegalArgumentException e) {
                send(chatId, "Could not switch character: " + e.getMessage());
            }
            return true;
        }

        if (cmd.equals("/profiledisable") || cmd.equals("/profileenable")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            String[] parts = text.split("\\s+");
            if (parts.length != 2) {
                send(chatId, "Usage: " + cmd + " <profile>");
                return true;
            }
            boolean active = cmd.equals("/profileenable");
            try {
                trackedPlayerService.setProfileActive(parts[1], active);
                send(chatId, "Profile " + parts[1] + (active ? " enabled." : " disabled."));
            } catch (IllegalArgumentException e) {
                send(chatId, "Could not update profile: " + e.getMessage());
            }
            return true;
        }

        if (cmd.equals("/vaultremindernow")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            send(chatId, vaultReminderService.checkNowMessage());
            return true;
        }

        return false;
    }

    private boolean handleWarcraftInformationCommand(CommandContext context) {
        long chatId = context.chatId();
        String text = context.text();
        String cmd = context.command();

        if (cmd.equals("/avginterrupts") || cmd.equals("/wclinterrupts")) {
            send(chatId, formatWarcraftLogsStatistic(
                    "TOP INTRUPT MASINA",
                    "Average interrupts per logged M+ dungeon",
                    PlayerStatistics::averageInterrupts,
                    " interrupts",
                    PlayerStatistics::dungeonRuns,
                    "logged dungeons"
            ));
            return true;
        }

        if (cmd.equals("/avgdeaths") || cmd.equals("/wcldeaths")) {
            send(chatId, formatWarcraftLogsStatistic(
                    "MOST FLOOR POV",
                    "Average deaths per logged M+ dungeon",
                    PlayerStatistics::averageDeaths,
                    " deaths",
                    PlayerStatistics::dungeonRuns,
                    "logged dungeons"
            ));
            return true;
        }

        if (cmd.equals("/avglogs") || cmd.equals("/avgparse") || cmd.equals("/wclaverage")) {
            send(chatId, formatWarcraftLogsStatistic(
                    null,
                    "Average per-key Warcraft Logs parse",
                    PlayerStatistics::averageParsePercentage,
                    "%",
                    PlayerStatistics::parsedDungeonRuns,
                    "parsed dungeons"
            ));
            return true;
        }

        if (cmd.equals("/affixes")) {
            JsonNode data = raiderIoClient.getWeeklyAffixes("eu", "en");
            send(chatId, AffixFormatter.formatWeeklyAffixes(data));
            return true;
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
                return true;
            }

            send(chatId,
                    defaultGuildProps.guildName() + "\n" +
                    RaidProgressFormatter.formatRaidLine(g, raidKey) + "\nLast update: " + formatLastCrawled(last)
            );
            return true;
        }


        if (cmd.equals("/guildlist")) {
            var p = defaultGuildProps;
            JsonNode g = raiderIoClient.getGuildProfile(p.region(), p.realm(), p.guildName());

            StringBuilder sb = new StringBuilder("Available raids:\n");
            g.path("raid_progression").fieldNames().forEachRemaining(k -> sb.append("• ").append(k).append("\n"));

            send(chatId, sb.toString());
            return true;
        }

        return false;
    }

    private boolean handleEconomyCommand(CommandContext context) {
        return switch (context.command()) {
            case "/price" -> handled(() -> handlePrice(context));
            case "/priceah" -> handled(() -> handleAuctionHousePrice(context));
            case "/token" -> handled(() -> handleTokenPrice(context.chatId()));
            case "/mount-achiv" -> handled(() -> handleMountAchievement(context));
            case "/ores", "/ore" -> handled(() -> send(
                    context.chatId(),
                    formatWatchlistWithSilverGold("Ores (EU)", watchlistProps.ores())
            ));
            case "/herbs", "/herb" -> handled(() -> send(
                    context.chatId(),
                    formatWatchlistWithSilverGold("Herbs (EU)", watchlistProps.herbs())
            ));
            default -> false;
        };
    }

    private void handlePrice(CommandContext context) {
        String arguments = commandArguments(context);
        if (arguments.isBlank()) {
            send(context.chatId(), "Usage: /price <itemId|item name> [realm-if-itemId]\n"
                    + "Examples:\n/price 72092 Draenor\n/price wow token");
            return;
        }

        try {
            String[] idAndRealm = arguments.split("\\s+", 2);
            Long itemId = parseLong(idAndRealm[0]);
            if (itemId == null) {
                sendNamedItemPrice(context.chatId(), arguments);
            } else {
                String realm = idAndRealm.length == 2 ? idAndRealm[1].trim() : "";
                sendItemIdPrice(context.chatId(), itemId, realm);
            }
        } catch (Exception e) {
            send(context.chatId(), "Blizzard price lookup failed: " + e.getMessage());
        }
    }

    private void sendItemIdPrice(long chatId, long itemId, String realm) {
        ItemRef item = itemService.getById(itemId);
        String itemName = resolveDisplayName(item, itemId);
        if (!realm.isBlank()) {
            PriceResult result = auctionService.getRealmAverage(realm, itemId);
            send(chatId, formatPriceMessage("Realm " + realm, itemName, result));
            return;
        }

        PriceResult result = auctionService.getRegionAverage(itemId);
        if (!result.available() && isWowToken(itemName)) {
            result = auctionService.getWowTokenPrice();
            send(chatId, formatPriceMessage("WoW Token (EU)", itemName, result));
            return;
        }
        send(chatId, formatPriceMessage("Region avg (EU)", itemName, result));
    }

    private void sendNamedItemPrice(long chatId, String itemName) {
        ItemRef item = itemService.findByName(itemName);
        if (item == null) {
            send(chatId, "Item not found: " + itemName);
            return;
        }

        PriceResult result = isWowToken(item.name())
                ? auctionService.getWowTokenPrice()
                : auctionService.getRegionAverage(item.id());
        String scope = isWowToken(item.name()) ? "WoW Token (EU)" : "Region avg (EU)";
        send(chatId, formatPriceMessage(scope, item.name(), result));
    }

    private void handleAuctionHousePrice(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 4) {
            send(context.chatId(), "Usage: /priceah <connectedRealmId> <auctionHouseId> <itemId>\n"
                    + "Example: /priceah 1080 2 72092");
            return;
        }

        Long connectedRealmId = parseLong(parts[1]);
        Long auctionHouseId = parseLong(parts[2]);
        Long itemId = parseLong(parts[3]);
        if (connectedRealmId == null || auctionHouseId == null || itemId == null) {
            send(context.chatId(), "Invalid numbers. Example: /priceah 1080 2 72092");
            return;
        }

        try {
            PriceResult result = auctionService.getAuctionHouseAverage(connectedRealmId, auctionHouseId, itemId);
            ItemRef item = itemService.getById(itemId);
            send(context.chatId(), formatPriceMessage(
                    "AuctionHouse " + auctionHouseId,
                    resolveDisplayName(item, itemId),
                    result
            ));
        } catch (Exception e) {
            send(context.chatId(), "Blizzard price lookup failed: " + e.getMessage());
        }
    }

    private void handleTokenPrice(long chatId) {
        try {
            PriceResult result = auctionService.getWowTokenPrice();
            send(chatId, formatPriceMessage("WoW Token (EU)", "WoW Token", result));
        } catch (Exception e) {
            send(chatId, "Blizzard token lookup failed: " + e.getMessage());
        }
    }

    private void handleMountAchievement(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length < 3) {
            send(context.chatId(), "Usage: /mount-achiv <realm> <name>\n"
                    + "Example: /mount-achiv stormscale bucothered");
            return;
        }

        try {
            var progress = mountService.getMountProgress(parts[1], parts[2]);
            send(context.chatId(), "Insurmountable Collection: "
                    + formatMountAchievementProgress(progress.usable()));
        } catch (Exception e) {
            send(context.chatId(), formatMountLookupError(parts[1], parts[2], e));
        }
    }

    private static String commandArguments(CommandContext context) {
        return context.text().length() > context.command().length()
                ? context.text().substring(context.command().length()).trim()
                : "";
    }

    private static boolean handled(Runnable action) {
        action.run();
        return true;
    }

    private boolean handleSeasonCommand(CommandContext context) {
        long chatId = context.chatId();
        String cmd = context.command();

        if (cmd.equals("/title") || cmd.equals("/titlewatch")) {
            send(chatId, formatTitleWatch());
            return true;
        }

        if (cmd.equals("/title01") || cmd.equals("/title0.1") || cmd.equals("/title001")) {
            send(chatId, formatTitle01Watch());
            return true;
        }

        if (cmd.equals("/seasonrecap") || cmd.equals("/recap")) {
            send(chatId, formatSeasonRecap());
            return true;
        }

        if (cmd.equals("/seasonrecapdepleted")) {
            send(chatId, formatSeasonRecapDepleted());
            return true;
        }

        if (cmd.equals("/seasonrecapabandoned")) {
            send(chatId, formatSeasonRecapAbandoned());
            return true;
        }

        return false;
    }

    private boolean handleTravelCommand(CommandContext context) {
        Update update = context.update();
        long chatId = context.chatId();
        String text = context.text();
        String cmd = context.command();

        if (cmd.equals("/road") || cmd.equals("/travel") || cmd.equals("/timetogo")) {
            send(chatId, timeToGoCommands.formatCurrent(text));
            return true;
        }

        if (cmd.equals("/roadbest") || cmd.equals("/travelbest") || cmd.equals("/timetogobest")) {
            send(chatId, timeToGoCommands.formatBest(text));
            return true;
        }

        if (cmd.equals("/timetogoimport30") || cmd.equals("/roadimport30") || cmd.equals("/travelimport30")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            try {
                send(chatId, timeToGoCommands.submitHistoricalImport());
            } catch (Exception e) {
                send(chatId, "TomTom historical import submit failed: " + e.getMessage());
            }
            return true;
        }

        if (cmd.equals("/timetogoimportstatus") || cmd.equals("/roadimportstatus") || cmd.equals("/travelimportstatus")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            try {
                send(chatId, timeToGoCommands.refreshHistoricalImport());
            } catch (Exception e) {
                send(chatId, "TomTom historical import status failed: " + e.getMessage());
            }
            return true;
        }

        return false;
    }

    private boolean handleGeneralCommand(CommandContext context) {
        Update update = context.update();
        long chatId = context.chatId();
        String text = context.text();
        String cmd = context.command();

        if (cmd.equals("/vault")) {
            String[] parts = text.split("\\s+");
            if (parts.length == 1) {
                send(chatId, formatWeeklyVaultWatch());
                return true;
            }
            if (parts.length != 3 && parts.length != 4) {
                send(chatId, "Usage: /vault\nOptional Mythic+ lookup: /vault <realm> <name>");
                return true;
            }

            String region = parts.length >= 4 ? parts[1].toLowerCase() : "eu";
            String realm = parts.length >= 4 ? parts[2] : parts[1];
            String name = parts.length >= 4 ? parts[3] : parts[2];

            try {
                var progress = raiderIoClient.getWeeklyVaultProgress(region, realm, name);
                send(chatId, formatWeeklyVault(progress));
            } catch (Exception e) {
                send(chatId, "Couldn’t fetch weekly Mythic+ vault data for " + name + " on " + realm
                             + " (" + region + ").\n" +
                             "Use: /vault <realm> <name>\n" +
                             "Example: /vault stormscale bucothered");
            }
            return true;
        }

        if (checkRio(cmd, text, chatId)) return true;

        // Public help deliberately omits administration and TomTom commands.
        if (cmd.equals("/help") || cmd.equals("/commands")) {
            send(chatId, "Commands:\n/myid\n/avginterrupts\n/avgdeaths\n/avglogs\n/rio <region> <realm> <name>\n/vault\n/title\n/title01\n/seasonrecap\n/seasonrecapdepleted\n/seasonrecapabandoned\n/affixes\n/guild\n/guildlist\n/mount-achiv <realm> <name>\n/price <itemId|item name> [realm-if-itemId]\n/priceah <connectedRealmId> <auctionHouseId> <itemId>\n/token\n/ores\n/herbs");
            return true;
        }

        if (cmd.equals("/help-admin")) {
            if (!isAdmin(update)) {
                send(chatId, adminOnlyMessage());
                return true;
            }
            send(chatId, "All commands:\n/help\n/myid\n/groupid\n/users\n/useradd <telegramUserId> [display name]\n/userdisable <telegramUserId>\n/userenable <telegramUserId>\n/profiles\n/profileadd <profile> <region> <realm> <character>\n/profileswitch <profile> <region> <realm> <character>\n/profiledisable <profile>\n/profileenable <profile>\n/vaultremindernow\n/avginterrupts\n/avgdeaths\n/avglogs\n/rio <region> <realm> <name>\n/vault\n/title\n/title01\n/seasonrecap\n/seasonrecapdepleted\n/seasonrecapabandoned\n/affixes\n/guild\n/guildlist\n/road [zadar zagreb|zagreb zadar]\n/roadbest [zadar zagreb|zagreb zadar]\n/timetogoimport30\n/timetogoimportstatus\n/mount-achiv <realm> <name>\n/price <itemId|item name> [realm-if-itemId]\n/priceah <connectedRealmId> <auctionHouseId> <itemId>\n/token\n/ores\n/herbs");
            return true;
        }
        return false;
    }

    private ScheduledFuture<?> scheduleWorkingMessage(long chatId) {
        return workingMessageScheduler.schedule(
                () -> send(chatId, PEON_WORK_MESSAGES.get(
                        ThreadLocalRandom.current().nextInt(PEON_WORK_MESSAGES.size())
                )),
                1500,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void stopWorkingMessageScheduler() {
        workingMessageScheduler.shutdownNow();
    }

    private String formatWarcraftLogsStatistic(
            String winnerTitle,
            String title,
            Function<PlayerStatistics, BigDecimal> valueExtractor,
            String suffix,
            ToIntFunction<PlayerStatistics> runCountExtractor,
            String runCountLabel
    ) {
        List<PlayerStatistics> statistics;
        try {
            statistics = warcraftLogsStatisticsService.statistics();
        } catch (Exception e) {
            return "Warcraft Logs lookup failed: " + e.getMessage();
        }

        List<PlayerStatistics> sorted = statistics.stream()
                .sorted(Comparator.comparing(
                        valueExtractor,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
        StringBuilder sb = new StringBuilder();
        if (winnerTitle != null) {
            sorted.stream()
                    .filter(player -> valueExtractor.apply(player) != null)
                    .findFirst()
                    .ifPresent(winner -> sb.append(winnerTitle)
                            .append(": ")
                            .append(winner.profileName())
                            .append("\n\n"));
        }
        int recordedPlayerRuns = statistics.stream()
                .mapToInt(runCountExtractor)
                .sum();
        sb.append(title)
                .append("\nOnly ")
                .append(recordedPlayerRuns)
                .append(" recorded player-runs are included.\n");
        for (PlayerStatistics player : sorted) {
            sb.append("• ").append(player.profileName()).append(": ");
            BigDecimal value = valueExtractor.apply(player);
            if (value == null) {
                sb.append("n/a");
            } else {
                sb.append(value.stripTrailingZeros().toPlainString()).append(suffix);
            }
            sb.append(" (").append(runCountExtractor.applyAsInt(player))
                    .append(" ").append(runCountLabel).append(")");
            if (player.error() != null) {
                sb.append(" [").append(player.error()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String formatTelegramIdentity(Update update) {
        var user = update.getMessage().getFrom();
        if (user == null) {
            return "Telegram user information is unavailable for this message.";
        }

        String username = user.getUserName();
        return "Your Telegram user ID: " + user.getId() + "\n"
                + "Username: " + (username == null || username.isBlank() ? "not set" : "@" + username) + "\n"
                + "Chat ID: " + update.getMessage().getChatId();
    }

    private String formatTelegramUsers() {
        var users = telegramBotUserService.users();
        if (users.isEmpty()) {
            return "No Telegram users are registered. Use /useradd <telegramUserId> [display name].";
        }

        StringBuilder sb = new StringBuilder("Telegram bot users\n");
        for (var user : users) {
            sb.append("\n• ").append(user.getTelegramUserId());
            if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
                sb.append(" — ").append(user.getDisplayName());
            }
            if (!user.isActive()) {
                sb.append(" (disabled)");
            }
        }
        return sb.toString();
    }

    private boolean isAdmin(Update update) {
        return adminUserId > 0
                && update.getMessage().getFrom() != null
                && update.getMessage().getFrom().getId().longValue() == adminUserId;
    }

    private String adminOnlyMessage() {
        if (adminUserId <= 0) {
            return "Profile management is disabled because TELEGRAM_ADMIN_USER_ID is not configured. "
                    + "Send /myid, then add that numeric user ID to the server .env.";
        }
        return "This command can only be used by the configured bot administrator.";
    }

    private String formatPlayerProfiles() {
        var profiles = trackedPlayerService.profiles();
        if (profiles.isEmpty()) {
            return "No player profiles configured.";
        }

        StringBuilder sb = new StringBuilder("Player profiles\n");
        for (var profile : profiles) {
            sb.append("\n• ").append(profile.name());
            if (!profile.active()) {
                sb.append(" (disabled)");
            }
            sb.append("\n");
            for (var character : profile.characters()) {
                sb.append(character.selected() ? "  → " : "    ")
                        .append(character.name())
                        .append("-").append(character.realm())
                        .append(" (").append(character.region()).append(")");
                if (!character.active()) {
                    sb.append(" [inactive]");
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String formatSeasonRecap() {
        StringBuilder sb = new StringBuilder("Midnight Season 1 M+ combined recap\n");
        for (SeasonPlayerRunCounts result : getSeasonRunCounts()) {
            if (result.runCounts() == null) {
                sb.append("\n• ").append(result.player().name()).append(": completed-run data unavailable\n");
                continue;
            }

            var recap = result.runCounts();
            int completed = recap.dungeons().stream()
                    .mapToInt(RaiderIoClient.DungeonRunCount::total)
                    .sum();
            int timed = recap.dungeons().stream()
                    .mapToInt(RaiderIoClient.DungeonRunCount::timed)
                    .sum();
            int depleted = completed - timed;
            var abandoned = abandonedRunService.findLatest(
                    result.player().region(),
                    result.player().realm(),
                    result.player().name(),
                    MIDNIGHT_SEASON_ONE
            );

            sb.append("\n• ").append(recap.name())
                    .append(": ").append(timed).append(" timed | ")
                    .append(depleted).append(" depleted | ");
            if (abandoned.isEmpty()) {
                sb.append("abandoned not recorded\n")
                        .append("  Combined percentages: unavailable\n");
            } else {
                int abandonedRuns = abandoned.get().abandonedRuns();
                int attempts = completed + abandonedRuns;
                sb.append(abandonedRuns).append(" abandoned (recorded)\n")
                        .append("  Percentages: timed ").append(formatPercentage(timed, attempts))
                        .append(" | depleted ").append(formatPercentage(depleted, attempts))
                        .append(" | abandoned ").append(formatPercentage(abandonedRuns, attempts))
                        .append("\n");
            }

            appendMostPlayedAndDepleted(sb, recap.dungeons());
            abandoned.ifPresent(summary -> sb.append("  Most abandoned: ")
                    .append(summary.mostAbandonedDungeon())
                    .append(" (").append(summary.mostAbandonedDungeonRuns()).append(")\n"));
        }
        sb.append("\nPercentages use timed + depleted + recorded abandoned as the total.\n")
                .append("Data: https://raider.io");
        return sb.toString().trim();
    }

    private String formatSeasonRecapDepleted() {
        StringBuilder sb = new StringBuilder("Midnight Season 1 M+ timed/depleted recap\n");
        for (SeasonPlayerRunCounts result : getSeasonRunCounts()) {
            if (result.runCounts() == null) {
                sb.append("\n• ").append(result.player().name()).append(": data unavailable\n");
            } else {
                appendDepletedSeasonRecap(sb, result.runCounts());
            }
        }
        sb.append("\nData: https://raider.io");
        return sb.toString().trim();
    }

    private String formatSeasonRecapAbandoned() {
        StringBuilder sb = new StringBuilder("Midnight Season 1 M+ abandoned recap\n");
        for (TrackedPlayer player : trackedPlayerService.seasonRecapPlayers()) {
            var abandoned = abandonedRunService.findLatest(
                    player.region(),
                    player.realm(),
                    player.name(),
                    MIDNIGHT_SEASON_ONE
            );
            if (abandoned.isEmpty()) {
                sb.append("\n• ").append(player.name()).append(": not recorded\n");
                continue;
            }

            var summary = abandoned.get();
            sb.append("\n• ").append(summary.characterName())
                    .append(": ").append(summary.abandonedRuns()).append(" abandoned runs recorded")
                    .append(" (of ").append(summary.liveTrackedRuns()).append(" live-tracked attempts, ")
                    .append(formatPercentage(summary.abandonedRuns(), summary.liveTrackedRuns())).append(")\n")
                    .append("  Most abandoned: ").append(summary.mostAbandonedDungeon())
                    .append(" (").append(summary.mostAbandonedDungeonRuns()).append(")\n");
        }
        return sb.toString().trim();
    }

    private List<SeasonPlayerRunCounts> getSeasonRunCounts() {
        Instant now = Instant.now();
        List<TrackedPlayer> players = trackedPlayerService.seasonRecapPlayers();
        if (seasonRunCountsCache != null
                && seasonRunCountsCache.expiresAt().isAfter(now)
                && seasonRunCountsCache.players().equals(players)) {
            return seasonRunCountsCache.results();
        }

        boolean hadError = false;
        List<SeasonPlayerRunCounts> results = new ArrayList<>();
        for (TrackedPlayer player : players) {
            try {
                results.add(new SeasonPlayerRunCounts(player, raiderIoClient.getMPlusSeasonRunCounts(
                        player.region(),
                        player.realm(),
                        player.name(),
                        MIDNIGHT_SEASON_ONE
                )));
            } catch (Exception e) {
                hadError = true;
                results.add(new SeasonPlayerRunCounts(player, null));
            }
        }

        Duration ttl = hadError ? FAILED_SEASON_RECAP_CACHE_TTL : SEASON_RECAP_CACHE_TTL;
        List<SeasonPlayerRunCounts> cachedResults = List.copyOf(results);
        seasonRunCountsCache = new SeasonRunCountsCache(players, cachedResults, now.plus(ttl));
        return cachedResults;
    }

    private static void appendDepletedSeasonRecap(
            StringBuilder sb,
            RaiderIoClient.MPlusSeasonRunCounts recap
    ) {
        List<RaiderIoClient.DungeonRunCount> dungeons = recap.dungeons() == null
                ? List.of()
                : recap.dungeons();
        int total = dungeons.stream().mapToInt(RaiderIoClient.DungeonRunCount::total).sum();
        int timed = dungeons.stream().mapToInt(RaiderIoClient.DungeonRunCount::timed).sum();

        sb.append("\n• ").append(recap.name())
                .append(": ").append(total).append(" completed | ")
                .append(timed).append(" timed (").append(formatPercentage(timed, total)).append(") | ")
                .append(total - timed).append(" depleted\n");

        if (total == 0) {
            sb.append("  Most played: none\n")
                    .append("  Most depleted: none\n");
            return;
        }

        appendMostPlayedAndDepleted(sb, dungeons);
    }

    private static void appendMostPlayedAndDepleted(
            StringBuilder sb,
            List<RaiderIoClient.DungeonRunCount> dungeons
    ) {
        RaiderIoClient.DungeonRunCount mostPlayed = dungeons.stream()
                .max(Comparator.comparingInt(RaiderIoClient.DungeonRunCount::total))
                .orElse(null);
        RaiderIoClient.DungeonRunCount mostDepleted = dungeons.stream()
                .max(Comparator.comparingInt(RaiderIoClient.DungeonRunCount::depleted))
                .orElse(null);

        sb.append("  Most played: ").append(formatDungeonCount(mostPlayed, false)).append("\n")
                .append("  Most depleted: ")
                .append(mostDepleted == null || mostDepleted.depleted() == 0
                        ? "none"
                        : formatDungeonCount(mostDepleted, true))
                .append("\n");
    }

    private static String formatPercentage(int part, int total) {
        if (total <= 0) return "0%";
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    private static String formatDungeonCount(RaiderIoClient.DungeonRunCount dungeon, boolean depleted) {
        if (dungeon == null) return "none";
        int count = depleted ? dungeon.depleted() : dungeon.total();
        return dungeon.shortName() + " (" + count + ")";
    }

    private String formatWeeklyVaultWatch() {
        List<TrackedPlayer> players = trackedPlayerService.vaultWatchPlayers();
        if (players.isEmpty()) {
            return "No Mythic+ vault watch players configured.";
        }

        StringBuilder sb = new StringBuilder("Great Vault — Mythic+ only\n")
                .append("Delves and regular Mythic dungeons are not included.\n");
        for (TrackedPlayer player : players) {
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

        StringBuilder sb = new StringBuilder("Great Vault — Mythic+ only\n");
        sb.append("Delves and regular Mythic dungeons are not included.\n");
        sb.append(progress.name()).append(" - ").append(progress.realm()).append(" (").append(progress.region()).append(")\n");
        sb.append("Top weekly Mythic+ runs from Raider.IO: ").append(runs.size()).append("\n");
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
            return "no current-reset Mythic+ runs found";
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
        TrackedPlayer player = trackedPlayerService.titleZeroPointOneWatchPlayer().orElse(null);
        if (player == null) {
            return "No 0.1% title watch player configured.";
        }

        Instant now = Instant.now();
        List<TrackedPlayer> players = List.of(player);
        if (title01WatchCache != null
                && title01WatchCache.expiresAt().isAfter(now)
                && title01WatchCache.players().equals(players)) {
            return title01WatchCache.message();
        }

        var cutoff = raiderIoClient.getCurrentMPlusTitleCutoff(player.region(), "p999");
        BigDecimal cutoffScore = cutoff.score();
        StringBuilder sb = new StringBuilder("M+ 0.1% title watch\n");
        sb.append("Cutoff: ").append(formatScore(cutoffScore))
                .append("\nCutoff updated: ").append(formatCutoffUpdatedAt(cutoff.updatedAt()))
                .append("\n");
        appendTitlePrediction(sb, "p999", player.region());

        try {
            var score = raiderIoClient.getCurrentMPlusScore(player.region(), player.realm(), player.name());
            BigDecimal all = score.all();
            BigDecimal remaining = all == null ? null : cutoffScore.subtract(all).max(BigDecimal.ZERO);
            BigDecimal above = all == null ? null : all.subtract(cutoffScore).max(BigDecimal.ZERO);
            sb.append("• ").append(score.name()).append(": ").append(formatTitleScoreLine(all, remaining, above));
        } catch (Exception e) {
            sb.append("• ").append(player.name()).append(": error: ").append(e.getMessage());
        }

        String message = sb.toString().trim();
        title01WatchCache = new TitleWatchCache(message, players, now.plus(TITLE_WATCH_CACHE_TTL));
        return message;
    }

    private String formatTitleWatch() {
        List<TrackedPlayer> players = trackedPlayerService.titleWatchPlayers();
        if (players.isEmpty()) {
            return "No title watch players configured.";
        }

        Instant now = Instant.now();
        if (titleWatchCache != null
                && titleWatchCache.expiresAt().isAfter(now)
                && titleWatchCache.players().equals(players)) {
            return titleWatchCache.message();
        }

        var cutoff = raiderIoClient.getCurrentMPlusTitleCutoff(players.getFirst().region());
        BigDecimal cutoffScore = cutoff.score();

        List<TitleWatchResult> results = new ArrayList<>();
        for (TrackedPlayer player : players) {
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
        appendTitlePrediction(sb, "p990", players.getFirst().region());
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
        titleWatchCache = new TitleWatchCache(message, players, now.plus(TITLE_WATCH_CACHE_TTL));
        return message;
    }

    private void appendTitlePrediction(StringBuilder sb, String percentileKey, String region) {
        var prediction = getCachedTitlePrediction(percentileKey, region);
        if (prediction == null) {
            sb.append("Predicted season end: n/a\n");
            return;
        }

        sb.append("Predicted season end: ")
                .append(formatScore(prediction.predictedScore()))
                .append(" (").append(formatPredictionFor(prediction.predictionFor())).append(")")
                .append("\n");
    }

    private RaiderIoClient.MPlusTitlePrediction getCachedTitlePrediction(String percentileKey, String region) {
        Instant now = Instant.now();
        TitlePredictionCache cache = "p999".equals(percentileKey) ? title01PredictionCache : titlePredictionCache;
        if (cache != null && cache.expiresAt().isAfter(now) && cache.region().equalsIgnoreCase(region)) {
            return cache.prediction();
        }

        try {
            var prediction = raiderIoClient.getCurrentMPlusTitlePrediction(region, percentileKey);
            var nextCache = new TitlePredictionCache(prediction, region, now.plus(TITLE_PREDICTION_CACHE_TTL));
            if ("p999".equals(percentileKey)) {
                title01PredictionCache = nextCache;
            } else {
                titlePredictionCache = nextCache;
            }
            return prediction;
        } catch (Exception e) {
            return cache == null || !cache.region().equalsIgnoreCase(region) ? null : cache.prediction();
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

    private record TitleWatchCache(String message, List<TrackedPlayer> players, Instant expiresAt) {
    }

    private record TitlePredictionCache(
            RaiderIoClient.MPlusTitlePrediction prediction,
            String region,
            Instant expiresAt
    ) {
    }

    private record SeasonPlayerRunCounts(
            TrackedPlayer player,
            RaiderIoClient.MPlusSeasonRunCounts runCounts
    ) {
    }

    private record SeasonRunCountsCache(
            List<TrackedPlayer> players,
            List<SeasonPlayerRunCounts> results,
            Instant expiresAt
    ) {
    }

    @FunctionalInterface
    private interface CommandHandler {
        boolean handle(CommandContext context);
    }

    private record CommandContext(Update update, long chatId, Long senderUserId, String text, String command) {

        private static CommandContext from(Update update) {
            if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
                return null;
            }

            String text = update.getMessage().getText().trim();
            String command = text.split("\\s+")[0];
            int mentionSeparator = command.indexOf('@');
            if (mentionSeparator >= 0) {
                command = command.substring(0, mentionSeparator);
            }

            Long senderUserId = update.getMessage().getFrom() == null
                    ? null
                    : update.getMessage().getFrom().getId();
            return new CommandContext(update, update.getMessage().getChatId(), senderUserId, text, command);
        }
    }

}
