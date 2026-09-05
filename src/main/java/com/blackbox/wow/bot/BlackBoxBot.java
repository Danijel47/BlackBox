package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.helper.AffixFormatter;
import com.blackbox.wow.helper.RaidPicker;
import com.blackbox.wow.helper.RaidProgressFormatter;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.RaceToWorldFirstService;
import com.blackbox.wow.service.RaidReportService;
import com.blackbox.wow.service.MPlusDataCollectionService;
import com.blackbox.wow.service.MPlusProgressService;
import com.blackbox.wow.service.MPlusDungeonVaultService;
import com.blackbox.wow.service.MPlusPerformanceService;
import com.blackbox.wow.service.MPlusAdvancedService;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.MPlusSeasonReportService;
import com.blackbox.wow.service.MPlusTeamService;
import com.blackbox.wow.service.MPlusTitleWatchService;
import com.blackbox.wow.service.HousingSalesReportService;
import com.blackbox.wow.service.HousingSalesReportService.HousingRanking;
import com.blackbox.wow.service.HousingMarketUnavailableException;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.TelegramAccessPolicy;
import com.blackbox.wow.service.TelegramBotUserService;
import com.blackbox.wow.service.TelegramDailyPromptService;
import com.blackbox.wow.service.VaultReminderService;
import com.blackbox.wow.service.WowTokenReportService;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Component
public class BlackBoxBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final int INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS = 600;
    private static final Duration TOKEN_MONTH_LOOKBACK = Duration.ofDays(30);
    private static final String TOKEN_MONTH_LABEL = "last 30 days";
    private static final String TELEGRAM_USER_UNAVAILABLE =
            "Telegram user information is unavailable for this message.";
    private static final String TELEGRAM_USER_PREFIX = "Telegram user ";
    private static final String PROFILE_PREFIX = "Profile ";
    private static final String PROFILES_LABEL = "Profiles";
    private static final String BACK_LABEL = "Back";
    private static final String CHOOSE_PROFILE_PREFIX = "Choose a profile for ";
    private static final String USAGE_PREFIX = "Usage: ";
    private static final String ROAD_COMMAND = "/road";
    private static final String ROAD_BEST_COMMAND = "/roadbest";
    private static final String WOW_TOKEN_EU_SCOPE = "WoW Token (EU)";
    private static final String TOKEN_LOWEST_WEEK_COMMAND = "/token_lowest_week";
    private static final String TOKEN_LOWEST_MONTH_COMMAND = "/token_lowest_month";
    private static final String TOKEN_HIGHEST_WEEK_COMMAND = "/token_highest_week";
    private static final String TOKEN_HIGHEST_MONTH_COMMAND = "/token_highest_month";
    private static final String TOKEN_BEST_COMMAND = "/token_best";
    private static final String MPLUS_CALLBACK_PREFIX = "mplus:";
    private static final String MPLUS_ACTION_CALLBACK = "action";
    private static final String MPLUS_PROFILE_CALLBACK = "profile";
    private static final String MPLUS_PAIR_FIRST_CALLBACK = "pair_first";
    private static final String MPLUS_PAIR_CALLBACK = "pair";
    private static final String MPLUS_MENU_CALLBACK = "menu";
    private static final String MPLUS_REFRESH_MESSAGE = " Use /mplus to refresh the menu.";
    private static final String MPLUS_INACTIVE_PROFILE_MESSAGE =
            "That profile is no longer active." + MPLUS_REFRESH_MESSAGE;
    private static final String WOW_CALLBACK_PREFIX = "wow:";
    private static final String WOW_ADMIN_CALLBACK_PREFIX = "admin:";
    private static final String WOW_MENU_CALLBACK = "menu";
    private static final String WOW_COMMAND_CALLBACK = "command";
    private static final String WOW_PROFILES_CALLBACK = "profiles";
    private static final String WOW_CHARACTER_CALLBACK = "character";
    private static final String WOW_RAID_CALLBACK = "raid";
    private static final String RAID_PROGRESS_CALLBACK = "progress";
    private static final String RAID_VAULT_CALLBACK = "vault";
    private static final String RAID_COMBAT_CALLBACK = "combat";
    private static final String ALL_PROFILES_CALLBACK = "all";
    private static final String ALL_PROFILES_LABEL = "All Profiles";
    private static final String RAID_PROGRESS_LABEL = "Raid Progress";
    private static final String RAID_VAULT_LABEL = "Raid Vault";
    private static final String RAID_COMBAT_LABEL = "Raid Combat";
    private static final String ITEM_LEVEL_CALLBACK = "ilvl";
    private static final String ITEM_LEVEL_LABEL = "Item Level";
    private static final String ITEM_LEVEL_PREFIX = "Item level: ";
    private static final String NO_ACTIVE_PROFILES_MESSAGE = "No active profiles are available.";
    private static final String ADMIN_PUBLIC_CALLBACK = "public";
    private static final String ADMIN_USERS_CALLBACK = "users";
    private static final String ADMIN_USER_ACCESS_CALLBACK = "user_access";
    private static final String ADMIN_PROFILE_ACCESS_CALLBACK = "profile_access";
    private static final String ADMIN_USER_CALLBACK = "user";
    private static final String ADMIN_PROFILE_CALLBACK = "profile";
    private static final String ADMIN_ALTS_CALLBACK = "alts";
    private static final String ADMIN_ALT_CALLBACK = "alt";
    private static final String ADMIN_ALT_ADD_CALLBACK = "alt_add";
    private static final String HOUSING_TOP_ACTION = "housing_top";
    private static final String HOUSING_TOP_LABEL = "Housing Sales";
    private static final String ADMIN_HOUSING_CALLBACK = "housing";
    private static final String HOUSING_SALES_LABEL = "Sales/day";
    private static final String HOUSING_AVERAGE_LABEL = "Avg price";
    private static final String HOUSING_PRICE_LABEL = "Market price";
    private static final String HOUSING_DATA_UNAVAILABLE_MESSAGE =
            "Housing sales data is temporarily unavailable. Please try again later.";
    private static final String ENABLE_LABEL_PREFIX = "Enable ";
    private static final String DISABLE_LABEL_PREFIX = "Disable ";
    private static final String ENABLED_STATUS_SUFFIX = " enabled.";
    private static final String DISABLED_STATUS_SUFFIX = " disabled.";
    private static final String COULD_NOT_UPDATE_PREFIX = "Could not update ";
    private static final String NO_PLAYER_PROFILES_MESSAGE = "No player profiles are configured.";
    private static final String ADMIN_PROFILE_MISSING_MESSAGE =
            "That profile no longer exists. Use /wow_admin to refresh the menu.";
    private static final String ADMIN_INVALID_SELECTION_MESSAGE =
            "That admin selection is no longer valid. Use /wow_admin to start again.";
    private static final String FOREIGN_MAIN_SELECTION_MESSAGE =
            "You can’t change another player’s main character. Open /profiles to choose your own.";
    private static final Duration ALT_ADDITION_TTL = Duration.ofMinutes(10);
    private static final int INLINE_BUTTONS_PER_ROW = 2;
    private static final int MAX_PROFILE_BUTTONS = 90;
    private static final ZoneId ZAGREB_ZONE = ZoneId.of("Europe/Zagreb");
    private static final List<String> PEON_WORK_MESSAGES = List.of(
            "Work, work... fetching the data. 🛠️",
            "Zug zug! The peon is checking. 🔎",
            "Something need doing? Still working on it. ⛏️",
            "Back to work! Your result is being prepared. 🧱"
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
    private final long adminUserId;
    private final TelegramClient client;
    private final RaiderIoClient raiderIoClient;
    private final RaiderIoDefaultGuildProperties defaultGuildProps;
    private final WowWatchlistProperties watchlistProps;
    private final BlizzardAuctionService auctionService;
    private final WowTokenReportService wowTokenReportService;
    private final BlizzardItemService itemService;
    private final BlizzardMountService mountService;
    private final TimeToGoCommandService timeToGoCommands;
    private final TrackedPlayerService trackedPlayerService;
    private final VaultReminderService vaultReminderService;
    private final RaceToWorldFirstService raceToWorldFirstService;
    private final RaidReportService raidReportService;
    private final MPlusDataCollectionService mplusDataCollectionService;
    private final MPlusProgressService mplusProgressService;
    private final MPlusDungeonVaultService mplusDungeonVaultService;
    private final MPlusPerformanceService mplusPerformanceService;
    private final MPlusTeamService mplusTeamService;
    private final MPlusAdvancedService mplusAdvancedService;
    private final MPlusRunCorrelationService mplusRunCorrelationService;
    private final MPlusSeasonReportService mplusSeasonReportService;
    private final MPlusTitleWatchService mplusTitleWatchService;
    private final WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    private final TelegramAccessPolicy telegramAccessPolicy;
    private final TelegramBotUserService telegramBotUserService;
    private final TelegramDailyPromptService telegramDailyPromptService;
    private final HousingSalesReportService housingSalesReportService;
    private final List<CommandHandler> commandHandlers;
    private final Map<PendingAltKey, PendingAltAddition> pendingAltAdditions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService workingMessageScheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform()
                    .daemon(true)
                    .name("telegram-working-message")
                    .unstarted(runnable)
    );

    public BlackBoxBot(
            @Value("${telegram.blackbox.bot.token}") String token,
            @Value("${telegram.admin-user-id:0}") long adminUserId,
            @Qualifier("blackBoxTelegramClient") TelegramClient client,
            RaiderIoClient raiderIoClient,
            RaiderIoDefaultGuildProperties defaultGuildProps,
            WowWatchlistProperties watchlistProps,
            BlizzardAuctionService auctionService,
            WowTokenReportService wowTokenReportService,
            BlizzardItemService itemService,
            BlizzardMountService mountService,
            TimeToGoCommandService timeToGoCommands,
            TrackedPlayerService trackedPlayerService,
            VaultReminderService vaultReminderService,
            RaceToWorldFirstService raceToWorldFirstService,
            RaidReportService raidReportService,
            MPlusDataCollectionService mplusDataCollectionService,
            MPlusProgressService mplusProgressService,
            MPlusDungeonVaultService mplusDungeonVaultService,
            MPlusPerformanceService mplusPerformanceService,
            MPlusTeamService mplusTeamService,
            MPlusAdvancedService mplusAdvancedService,
            MPlusRunCorrelationService mplusRunCorrelationService,
            MPlusSeasonReportService mplusSeasonReportService,
            MPlusTitleWatchService mplusTitleWatchService,
            WarcraftLogsStatisticsService warcraftLogsStatisticsService,
            TelegramAccessPolicy telegramAccessPolicy,
            TelegramBotUserService telegramBotUserService,
            TelegramDailyPromptService telegramDailyPromptService,
            HousingSalesReportService housingSalesReportService
    ) {
        this.token = token;
        this.adminUserId = adminUserId;
        this.client = client;
        this.raiderIoClient = raiderIoClient;
        this.defaultGuildProps = defaultGuildProps;
        this.watchlistProps = watchlistProps;
        this.auctionService = auctionService;
        this.wowTokenReportService = wowTokenReportService;
        this.itemService = itemService;
        this.mountService = mountService;
        this.timeToGoCommands = timeToGoCommands;
        this.trackedPlayerService = trackedPlayerService;
        this.vaultReminderService = vaultReminderService;
        this.raceToWorldFirstService = raceToWorldFirstService;
        this.raidReportService = raidReportService;
        this.mplusDataCollectionService = mplusDataCollectionService;
        this.mplusProgressService = mplusProgressService;
        this.mplusDungeonVaultService = mplusDungeonVaultService;
        this.mplusPerformanceService = mplusPerformanceService;
        this.mplusTeamService = mplusTeamService;
        this.mplusAdvancedService = mplusAdvancedService;
        this.mplusRunCorrelationService = mplusRunCorrelationService;
        this.mplusSeasonReportService = mplusSeasonReportService;
        this.mplusTitleWatchService = mplusTitleWatchService;
        this.warcraftLogsStatisticsService = warcraftLogsStatisticsService;
        this.telegramAccessPolicy = telegramAccessPolicy;
        this.telegramBotUserService = telegramBotUserService;
        this.telegramDailyPromptService = telegramDailyPromptService;
        this.housingSalesReportService = housingSalesReportService;
        this.commandHandlers = List.of(
                this::handleUserAdministrationCommand,
                this::handlePlayerProfileCommand,
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
        if (update != null && update.hasMessage()) {
            telegramDailyPromptService.onMessage(senderUserId(update));
            if (handlePendingAltAddition(update)) {
                return;
            }
        }
        if (update != null && update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }
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

    private static Long senderUserId(Update update) {
        if (update == null) {
            return null;
        }
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getId();
        }
        return null;
    }

    private void dispatchCommand(CommandContext context) {
        for (CommandHandler handler : commandHandlers) {
            if (handler.handle(context)) {
                return;
            }
        }
    }

    private boolean handleUserAdministrationCommand(CommandContext context) {
        Runnable adminAction = switch (context.command()) {
            case "/groupid", "/chatid" -> () -> send(context.chatId(), "Chat ID: " + context.chatId());
            case "/users", "/userlist" -> () -> send(context.chatId(), formatTelegramUsers());
            case "/useradd" -> () -> addTelegramUser(context);
            case "/userdisable", "/userenable" -> () -> changeTelegramUserStatus(context);
            case "/housingtop" -> () -> sendHousingTop(context.chatId());
            default -> null;
        };
        if (adminAction == null) {
            return false;
        }
        runAdminCommand(context, adminAction);
        return true;
    }

    private void sendHousingTop(long chatId) {
        sendHousingReport(chatId, HousingRanking.SALES);
    }

    private void sendHousingReport(long chatId, HousingRanking ranking) {
        InlineKeyboardMarkup replyMarkup = housingRankingKeyboard();
        try {
            send(chatId, housingSalesReportService.rankedMessage(ranking), replyMarkup);
        } catch (HousingMarketUnavailableException e) {
            send(chatId, e.adminMessage(), replyMarkup);
        } catch (RuntimeException _) {
            send(chatId, HOUSING_DATA_UNAVAILABLE_MESSAGE, replyMarkup);
        }
    }

    private void addTelegramUser(CommandContext context) {
        String[] parts = context.text().split("\\s+", 3);
        if (parts.length < 2) {
            send(context.chatId(), """
                    Usage: /user_add <telegramUserId> [display name]
                    Example: /user_add 123456789 Alice
                    """.strip());
            return;
        }
        Long telegramUserId = parsePositiveTelegramUserId(parts[1], context.chatId());
        if (telegramUserId == null) {
            return;
        }
        String displayName = parts.length == 3 ? parts[2].trim() : null;
        try {
            telegramBotUserService.addOrEnable(telegramUserId, displayName);
            telegramAccessPolicy.userAccessChanged(telegramUserId);
            send(context.chatId(), TELEGRAM_USER_PREFIX + telegramUserId + " is now allowed.");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not add user: " + e.getMessage());
        }
    }

    private void changeTelegramUserStatus(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        boolean active = context.command().equals("/userenable");
        if (parts.length != 2) {
            send(context.chatId(), USAGE_PREFIX + (active ? "/user_enable" : "/user_disable")
                    + " <telegramUserId>");
            return;
        }
        Long telegramUserId = parsePositiveTelegramUserId(parts[1], context.chatId());
        if (telegramUserId == null) {
            return;
        }
        try {
            telegramBotUserService.setActive(telegramUserId, active);
            telegramAccessPolicy.userAccessChanged(telegramUserId);
            send(context.chatId(), TELEGRAM_USER_PREFIX + telegramUserId
                    + statusSuffix(active));
        } catch (IllegalArgumentException e) {
            send(context.chatId(), COULD_NOT_UPDATE_PREFIX + "user: " + e.getMessage());
        }
    }

    private Long parsePositiveTelegramUserId(String value, long chatId) {
        Long telegramUserId = parseLong(value);
        if (telegramUserId == null || telegramUserId <= 0) {
            send(chatId, "Telegram user ID must be a positive number.");
            return null;
        }
        return telegramUserId;
    }

    private boolean handlePlayerProfileCommand(CommandContext context) {
        return switch (context.command()) {
            case "/mplus" -> handled(() -> handleUnifiedMPlusCommand(context));
            case "/profiles" -> handled(() -> sendProfilesMenu(context.chatId()));
            case "/profile-help" -> handled(() -> send(context.chatId(), profileHelpMessage()));
            case "/profile" -> handled(() -> sendOwnProfile(context));
            case "/profilemain" -> handled(() -> changeOwnMain(context));
            case "/mains" -> handled(() -> send(context.chatId(), formatCurrentMains()));
            default -> false;
        };
    }

    private void handleUnifiedMPlusCommand(CommandContext context) {
        if (commandArguments(context).isBlank()) {
            sendMPlusMenu(context.chatId());
            return;
        }
        MPlusRequest request = MPlusRequest.from(context.text());
        send(context.chatId(), mPlusResponse(request.section(), request.arguments(), context.senderUserId()));
    }

    private String mPlusResponse(String section, String arguments, Long senderUserId) {
        return switch (section) {
            case RAID_PROGRESS_CALLBACK -> mplusProgressService.progressMessage(arguments, senderUserId);
            case "dungeons" -> mplusDungeonVaultService.dungeonCoverageMessage(
                    arguments, senderUserId
            );
            case RAID_VAULT_CALLBACK -> mplusDungeonVaultService.currentVaultMessage(
                    arguments, senderUserId
            );
            case "performance" -> mplusPerformanceService.performanceMessage(
                    arguments, senderUserId
            );
            case "highlights" -> mplusPerformanceService.highlightsMessage(
                    arguments, senderUserId
            );
            case "team" -> mplusTeamService.teamMessage(arguments, senderUserId);
            case MPLUS_PAIR_CALLBACK -> mplusTeamService.pairMessage(arguments);
            case "consistency" -> mplusAdvancedService.consistencyMessage(
                    arguments, senderUserId
            );
            case "awards" -> warcraftLogsStatisticsService.awardsMessage();
            case "coverage" -> mplusRunCorrelationService.coverageMessage(
                    arguments, senderUserId
            );
            case RAID_COMBAT_CALLBACK -> warcraftLogsStatisticsService.combatMessage(arguments);
            case "status" -> adminMPlusStatus(senderUserId);
            default -> mplusHelpMessage();
        };
    }

    private String adminMPlusStatus(Long senderUserId) {
        if (!isAdmin(senderUserId)) {
            return adminOnlyMessage();
        }
        return """
                %s

                %s
                """.formatted(
                mplusDataCollectionService.statusMessage(),
                mplusRunCorrelationService.statusMessage(warcraftLogsStatisticsService.seasonKey())
        ).strip();
    }

    private static String mplusHelpMessage() {
        return """
                M+ commands:
                /mplus_progress [profile]
                /mplus_dungeons [profile]
                /mplus_vault [profile] — current week only
                /mplus_performance [profile]
                /mplus_highlights [profile]
                /mplus_team [profile]
                /mplus_pair <profile-a> <profile-b>
                /mplus_consistency [profile]
                /mplus_awards
                /mplus_coverage [profile]
                /mplus_combat [profile]
                """.strip();
    }

    private void sendMPlusMenu(long chatId) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (MPlusMenuAction action : MPlusMenuAction.values()) {
            if (action == MPlusMenuAction.STATUS) {
                continue;
            }
            buttons.add(inlineButton(
                    action.label(),
                    MPLUS_CALLBACK_PREFIX + MPLUS_ACTION_CALLBACK + ":" + action.key()
            ));
        }
        buttons.add(inlineButton("WoW Menu", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
        send(chatId, "Choose a Mythic+ report:", inlineKeyboard(buttons));
    }

    private void handleCallback(CallbackQuery callback) {
        if (callback == null || !isSupportedCallback(callback.getData())) {
            return;
        }
        answerCallback(callback.getId());
        if (callback.getMessage() == null || callback.getFrom() == null) {
            return;
        }
        Long chatId = callback.getMessage().getChatId();
        Long senderUserId = callback.getFrom().getId();
        if (chatId == null || senderUserId == null) {
            return;
        }
        if (!telegramAccessPolicy.isAllowed(chatId, senderUserId)) {
            return;
        }
        if (isForeignMainSelection(callback.getData(), senderUserId)) {
            send(chatId, FOREIGN_MAIN_SELECTION_MESSAGE);
            return;
        }

        removeInlineKeyboard(chatId, callback.getMessage().getMessageId());
        ScheduledFuture<?> workingMessage = scheduleWorkingMessage(chatId);
        try {
            routeCallback(chatId, senderUserId, callback.getData());
        } catch (RuntimeException _) {
            send(chatId, "Could not load that menu selection. Please try again.");
        } finally {
            workingMessage.cancel(false);
        }
    }

    private static boolean isSupportedCallback(String callbackData) {
        return callbackData != null && (callbackData.startsWith(MPLUS_CALLBACK_PREFIX)
                || callbackData.startsWith(WOW_CALLBACK_PREFIX)
                || callbackData.startsWith(WOW_ADMIN_CALLBACK_PREFIX));
    }

    private static boolean isForeignMainSelection(String callbackData, long senderUserId) {
        String selectionPrefix = WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":select:";
        if (!callbackData.startsWith(selectionPrefix)) {
            return false;
        }
        String[] parts = callbackData.split(":");
        Long ownerUserId = parts.length == 5 ? parseLong(parts[3]) : null;
        return ownerUserId != null && ownerUserId != senderUserId;
    }

    private void routeCallback(long chatId, long senderUserId, String callbackData) {
        if (callbackData.startsWith(MPLUS_CALLBACK_PREFIX)) {
            routeMPlusCallback(chatId, senderUserId, callbackData);
        } else if (callbackData.startsWith(WOW_ADMIN_CALLBACK_PREFIX)) {
            routeWowAdminCallback(chatId, senderUserId, callbackData);
        } else {
            routeWowCallback(chatId, senderUserId, callbackData);
        }
    }

    private void routeMPlusCallback(long chatId, long senderUserId, String callbackData) {
        String[] parts = callbackData.split(":");
        if (parts.length == 2 && parts[1].equals(MPLUS_MENU_CALLBACK)) {
            sendMPlusMenu(chatId);
            return;
        }
        if (parts.length == 3 && parts[1].equals(MPLUS_ACTION_CALLBACK)) {
            selectMPlusAction(chatId, senderUserId, MPlusMenuAction.fromKey(parts[2]));
            return;
        }
        if (parts.length == 4 && parts[1].equals(MPLUS_PROFILE_CALLBACK)) {
            runMPlusProfileAction(chatId, senderUserId, MPlusMenuAction.fromKey(parts[2]), parts[3]);
            return;
        }
        if (parts.length == 3 && parts[1].equals(MPLUS_PAIR_FIRST_CALLBACK)) {
            selectSecondPairProfile(chatId, parts[2]);
            return;
        }
        if (parts.length == 4 && parts[1].equals(MPLUS_PAIR_CALLBACK)) {
            runMPlusPairAction(chatId, parts[2], parts[3]);
            return;
        }
        send(chatId, "That Mythic+ menu selection is no longer valid." + MPLUS_REFRESH_MESSAGE);
    }

    private void selectMPlusAction(long chatId, long senderUserId, MPlusMenuAction action) {
        if (action == null) {
            send(chatId, "That Mythic+ report is unavailable." + MPLUS_REFRESH_MESSAGE);
            return;
        }
        if (action == MPlusMenuAction.PAIR) {
            sendProfileMenu(chatId, "Choose the first profile:", MPLUS_PAIR_FIRST_CALLBACK, null, false);
            return;
        }
        if (action.requiresProfile()) {
            sendProfileMenu(
                    chatId,
                    CHOOSE_PROFILE_PREFIX + action.label() + ":",
                    MPLUS_PROFILE_CALLBACK + ":" + action.key(),
                    null,
                    action.supportsAllProfiles()
            );
            return;
        }
        send(chatId, mPlusResponse(action.key(), "", senderUserId));
    }

    private void runMPlusProfileAction(
            long chatId,
            long senderUserId,
            MPlusMenuAction action,
            String profileIdValue
    ) {
        if (action == null || !action.requiresProfile()) {
            send(chatId, "That Mythic+ report is unavailable." + MPLUS_REFRESH_MESSAGE);
            return;
        }
        if (action.supportsAllProfiles() && ALL_PROFILES_CALLBACK.equals(profileIdValue)) {
            send(chatId, mPlusResponse(action.key(), "", senderUserId));
            return;
        }
        TrackedPlayer profile = findActiveProfile(profileIdValue);
        if (profile == null) {
            send(chatId, MPLUS_INACTIVE_PROFILE_MESSAGE);
            return;
        }
        send(chatId, mPlusResponse(action.key(), profile.profileName(), senderUserId));
    }

    private void selectSecondPairProfile(long chatId, String firstProfileIdValue) {
        TrackedPlayer firstProfile = findActiveProfile(firstProfileIdValue);
        if (firstProfile == null) {
            send(chatId, MPLUS_INACTIVE_PROFILE_MESSAGE);
            return;
        }
        sendProfileMenu(
                chatId,
                "Pair " + firstProfile.profileName() + " with:",
                MPLUS_PAIR_CALLBACK + ":" + firstProfile.profileId(),
                firstProfile.profileId(),
                false
        );
    }

    private void runMPlusPairAction(long chatId, String firstProfileIdValue, String secondProfileIdValue) {
        TrackedPlayer firstProfile = findActiveProfile(firstProfileIdValue);
        TrackedPlayer secondProfile = findActiveProfile(secondProfileIdValue);
        if (firstProfile == null || secondProfile == null || firstProfile.profileId() == secondProfile.profileId()) {
            send(chatId, "That profile pair is no longer valid." + MPLUS_REFRESH_MESSAGE);
            return;
        }
        send(chatId, mPlusResponse(
                MPlusMenuAction.PAIR.key(),
                firstProfile.profileName() + " " + secondProfile.profileName(),
                null
        ));
    }

    private void sendProfileMenu(
            long chatId,
            String prompt,
            String callbackAction,
            Long excludedProfileId,
            boolean includeAllProfiles
    ) {
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        if (includeAllProfiles) {
            buttons.add(inlineButton(
                    ALL_PROFILES_LABEL,
                    MPLUS_CALLBACK_PREFIX + callbackAction + ":" + ALL_PROFILES_CALLBACK
            ));
        }
        for (TrackedPlayer profile : profiles) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            if (excludedProfileId == null || profile.profileId() != excludedProfileId) {
                buttons.add(inlineButton(
                        profile.profileName(),
                        MPLUS_CALLBACK_PREFIX + callbackAction + ":" + profile.profileId()
                ));
            }
        }
        if (buttons.isEmpty()) {
            send(chatId, "No active profiles are available for that Mythic+ report.");
            return;
        }
        buttons.add(inlineButton(BACK_LABEL, MPLUS_CALLBACK_PREFIX + MPLUS_MENU_CALLBACK));
        send(chatId, prompt, inlineKeyboard(buttons));
    }

    private TrackedPlayer findActiveProfile(String profileIdValue) {
        Long profileId = parseLong(profileIdValue);
        if (profileId == null || profileId <= 0) {
            return null;
        }
        return trackedPlayerService.activePlayers().stream()
                .filter(profile -> profile.profileId() == profileId)
                .findFirst()
                .orElse(null);
    }

    private static InlineKeyboardButton inlineButton(String label, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(callbackData)
                .build();
    }

    private static InlineKeyboardMarkup inlineKeyboard(List<InlineKeyboardButton> buttons) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int index = 0; index < buttons.size(); index += INLINE_BUTTONS_PER_ROW) {
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(buttons.get(index));
            if (index + 1 < buttons.size()) {
                row.add(buttons.get(index + 1));
            }
            rows.add(row);
        }
        return new InlineKeyboardMarkup(rows);
    }

    private void sendWowMenu(long chatId) {
        send(chatId, "Choose a WoW section:", inlineKeyboard(List.of(
                wowMenuButton("Mythic+", "mplus"),
                wowMenuButton(PROFILES_LABEL, WOW_PROFILES_CALLBACK),
                wowMenuButton("Character", WOW_CHARACTER_CALLBACK),
                wowMenuButton("Raids", "raids"),
                wowMenuButton("Season", "season"),
                wowMenuButton("Tokens", "tokens"),
                wowMenuButton("Materials", "materials")
        )));
    }

    private static InlineKeyboardButton wowMenuButton(String label, String menuKey) {
        return inlineButton(label, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":" + menuKey);
    }

    private void routeWowCallback(long chatId, long senderUserId, String callbackData) {
        String[] parts = callbackData.split(":");
        if (parts.length == 2 && parts[1].equals(WOW_MENU_CALLBACK)) {
            sendWowMenu(chatId);
            return;
        }
        if (parts.length == 3 && parts[1].equals(WOW_MENU_CALLBACK)) {
            openWowSubmenu(chatId, parts[2]);
            return;
        }
        if (parts.length == 3 && parts[1].equals(WOW_COMMAND_CALLBACK)) {
            runWowCommand(chatId, senderUserId, parts[2]);
            return;
        }
        if (parts.length >= 3 && parts[1].equals(WOW_PROFILES_CALLBACK)) {
            routeProfilesCallback(chatId, senderUserId, parts);
            return;
        }
        if (parts.length >= 3 && parts[1].equals(WOW_CHARACTER_CALLBACK)) {
            routeCharacterCallback(chatId, parts);
            return;
        }
        if (parts.length >= 3 && parts[1].equals(WOW_RAID_CALLBACK)) {
            routeRaidCallback(chatId, parts);
            return;
        }
        send(chatId, "That WoW menu selection is no longer valid. Use /wow to start again.");
    }

    private void openWowSubmenu(long chatId, String menuKey) {
        switch (menuKey) {
            case "mplus" -> sendMPlusMenu(chatId);
            case WOW_PROFILES_CALLBACK -> sendProfilesMenu(chatId);
            case WOW_CHARACTER_CALLBACK -> sendCharacterMenu(chatId);
            case "raids" -> sendRaidMenu(chatId);
            case "season" -> sendCommandMenu(chatId, "Choose a season report:", WowMenuGroup.SEASON);
            case "tokens" -> sendCommandMenu(chatId, "Choose a token report:", WowMenuGroup.TOKENS);
            case "materials" -> sendCommandMenu(chatId, "Choose a material list:", WowMenuGroup.MATERIALS);
            case "travel" -> sendCommandMenu(chatId, "Choose a travel report:", WowMenuGroup.TRAVEL);
            default -> send(chatId, "That WoW section is unavailable. Use /wow to refresh the menu.");
        }
    }

    private void sendCommandMenu(long chatId, String prompt, WowMenuGroup group) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (WowCommandAction action : WowCommandAction.values()) {
            if (action.group() == group) {
                buttons.add(inlineButton(
                        action.label(),
                        WOW_CALLBACK_PREFIX + WOW_COMMAND_CALLBACK + ":" + action.key()
                ));
            }
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
        send(chatId, prompt, inlineKeyboard(buttons));
    }

    private void runWowCommand(long chatId, long senderUserId, String actionKey) {
        WowCommandAction action = WowCommandAction.fromKey(actionKey);
        if (action == null) {
            send(chatId, "That WoW report is unavailable. Use /wow to refresh the menu.");
            return;
        }
        dispatchCommand(CommandContext.forCallback(chatId, senderUserId, action.commandText()));
    }

    private void sendRaidMenu(long chatId) {
        send(chatId, "Choose a raid report:", inlineKeyboard(List.of(
                inlineButton("World First", WOW_CALLBACK_PREFIX + WOW_COMMAND_CALLBACK + ":rwf"),
                inlineButton(RAID_PROGRESS_LABEL, WOW_CALLBACK_PREFIX + WOW_RAID_CALLBACK + ":"
                        + RAID_PROGRESS_CALLBACK),
                inlineButton(RAID_VAULT_LABEL, WOW_CALLBACK_PREFIX + WOW_RAID_CALLBACK + ":"
                        + RAID_VAULT_CALLBACK),
                inlineButton(RAID_COMBAT_LABEL, WOW_CALLBACK_PREFIX + WOW_RAID_CALLBACK + ":"
                        + RAID_COMBAT_CALLBACK),
                inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        )));
    }

    private void routeRaidCallback(long chatId, String[] parts) {
        if (parts.length == 3 && isRaidAction(parts[2])) {
            sendRaidProfileMenu(chatId, parts[2]);
            return;
        }
        if (parts.length == 4 && isRaidAction(parts[2])) {
            runRaidReport(chatId, parts[2], parts[3]);
            return;
        }
        send(chatId, "That raid report is no longer available. Use /wow to refresh the menu.");
    }

    private void sendRaidProfileMenu(long chatId, String action) {
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        if (profiles.isEmpty()) {
            send(chatId, NO_ACTIVE_PROFILES_MESSAGE);
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(inlineButton(
                ALL_PROFILES_LABEL,
                WOW_CALLBACK_PREFIX + WOW_RAID_CALLBACK + ":" + action + ":" + ALL_PROFILES_CALLBACK
        ));
        for (TrackedPlayer profile : profiles) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            buttons.add(inlineButton(
                    profile.profileName(),
                    WOW_CALLBACK_PREFIX + WOW_RAID_CALLBACK + ":" + action + ":" + profile.profileId()
            ));
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":raids"));
        send(chatId, CHOOSE_PROFILE_PREFIX + raidActionLabel(action) + ":", inlineKeyboard(buttons));
    }

    private void runRaidReport(long chatId, String action, String profileIdValue) {
        List<TrackedPlayer> players = raidReportPlayersById(profileIdValue);
        if (players.isEmpty()) {
            send(chatId, "That raid profile is no longer active. Use /wow to refresh the menu.");
            return;
        }
        String report = switch (action) {
            case RAID_PROGRESS_CALLBACK -> raidReportService.progress(players);
            case RAID_VAULT_CALLBACK -> raidReportService.weeklyVault(players);
            case RAID_COMBAT_CALLBACK -> warcraftLogsStatisticsService.raidCombatMessage(players);
            default -> "That raid report is unavailable.";
        };
        send(chatId, report);
    }

    private List<TrackedPlayer> raidReportPlayersById(String profileIdValue) {
        if (ALL_PROFILES_CALLBACK.equals(profileIdValue)) {
            return trackedPlayerService.activePlayers();
        }
        TrackedPlayer profile = findActiveProfile(profileIdValue);
        return profile == null ? List.of() : List.of(profile);
    }

    private static boolean isRaidAction(String action) {
        return RAID_PROGRESS_CALLBACK.equals(action)
                || RAID_VAULT_CALLBACK.equals(action)
                || RAID_COMBAT_CALLBACK.equals(action);
    }

    private static String raidActionLabel(String action) {
        return switch (action) {
            case RAID_PROGRESS_CALLBACK -> RAID_PROGRESS_LABEL;
            case RAID_VAULT_CALLBACK -> RAID_VAULT_LABEL;
            case RAID_COMBAT_CALLBACK -> RAID_COMBAT_LABEL;
            default -> "Raid Report";
        };
    }

    private void sendProfilesMenu(long chatId) {
        send(chatId, "Choose a profile action:", inlineKeyboard(List.of(
                inlineButton("My Profile", WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":view"),
                inlineButton("Select Main", WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":main"),
                inlineButton("Group Mains", WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":mains"),
                inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        )));
    }

    private void routeProfilesCallback(long chatId, long senderUserId, String[] parts) {
        if (parts.length == 3 && parts[2].equals("view")) {
            dispatchCommand(CommandContext.forCallback(chatId, senderUserId, "/profile"));
        } else if (parts.length == 3 && parts[2].equals("mains")) {
            dispatchCommand(CommandContext.forCallback(chatId, senderUserId, "/mains"));
        } else if (parts.length == 3 && parts[2].equals("main")) {
            sendOwnedCharacterMenu(chatId, senderUserId);
        } else if (parts.length == 5 && parts[2].equals("select")) {
            selectOwnedCharacter(chatId, senderUserId, parts[3], parts[4]);
        } else {
            send(chatId, "That profile selection is no longer valid. Use /profiles to start again.");
        }
    }

    private void sendOwnedCharacterMenu(long chatId, long senderUserId) {
        var profile = trackedPlayerService.profileForTelegramUser(senderUserId);
        if (profile.isEmpty()) {
            send(chatId, "No player profile is linked to your Telegram account.");
            return;
        }
        List<TrackedPlayerService.ProfileCharacter> characters = activeCharacters(profile.get());
        if (characters.isEmpty()) {
            send(chatId, "Your profile has no active characters.");
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (int index = 0; index < characters.size(); index++) {
            var character = characters.get(index);
            String selectedMarker = character.selected() ? " ✓" : "";
            buttons.add(inlineButton(
                    character.name() + "-" + character.realm() + selectedMarker,
                    WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":select:" + senderUserId + ":" + index
            ));
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":" + WOW_PROFILES_CALLBACK));
        send(chatId, "Choose your main character:", inlineKeyboard(buttons));
    }

    private void selectOwnedCharacter(
            long chatId,
            long senderUserId,
            String ownerUserIdValue,
            String characterIndexValue
    ) {
        Long ownerUserId = parseLong(ownerUserIdValue);
        if (ownerUserId == null || ownerUserId != senderUserId) {
            send(chatId, FOREIGN_MAIN_SELECTION_MESSAGE);
            return;
        }
        Long characterIndex = parseLong(characterIndexValue);
        var profile = trackedPlayerService.profileForTelegramUser(senderUserId);
        if (characterIndex == null || characterIndex < 0 || profile.isEmpty()) {
            send(chatId, "That character selection is no longer valid. Open /profiles again.");
            return;
        }
        List<TrackedPlayerService.ProfileCharacter> characters = activeCharacters(profile.get());
        if (characterIndex >= characters.size()) {
            send(chatId, "That character selection is no longer valid. Open /profiles again.");
            return;
        }
        var character = characters.get(characterIndex.intValue());
        trackedPlayerService.switchOwnedCharacter(senderUserId, character.realm(), character.name());
        send(chatId, "Your selected main is now " + character.name() + "-" + character.realm() + " (EU).",
                inlineKeyboard(List.of(
                        inlineButton(PROFILES_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":"
                                + WOW_PROFILES_CALLBACK),
                        inlineButton("WoW Menu", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
                )));
    }

    private static List<TrackedPlayerService.ProfileCharacter> activeCharacters(
            TrackedPlayerService.PlayerProfile profile
    ) {
        return profile.characters().stream()
                .filter(TrackedPlayerService.ProfileCharacter::active)
                .toList();
    }

    private void sendCharacterMenu(long chatId) {
        send(chatId, "Choose a character report:", inlineKeyboard(List.of(
                inlineButton("Raider.IO Score", WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":rio"),
                inlineButton(ITEM_LEVEL_LABEL,
                        WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":" + ITEM_LEVEL_CALLBACK),
                inlineButton("Mount Progress", WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":mount"),
                inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        )));
    }

    private void routeCharacterCallback(long chatId, String[] parts) {
        if (parts.length == 3 && CharacterReportAction.isSupported(parts[2])) {
            sendCharacterProfileMenu(chatId, parts[2]);
            return;
        }
        if (parts.length == 4) {
            runCharacterReport(chatId, parts[2], parts[3]);
            return;
        }
        send(chatId, "That character report is no longer valid. Use /wow to start again.");
    }

    private void sendCharacterProfileMenu(long chatId, String actionKey) {
        CharacterReportAction action = CharacterReportAction.fromKey(actionKey);
        if (action == null) {
            send(chatId, "That character report is unavailable.");
            return;
        }
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        if (profiles.isEmpty()) {
            send(chatId, NO_ACTIVE_PROFILES_MESSAGE);
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(inlineButton(
                ALL_PROFILES_LABEL,
                WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":" + action.key() + ":all"
        ));
        for (TrackedPlayer profile : profiles) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            buttons.add(inlineButton(
                    profile.profileName(),
                    WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":" + action.key() + ":" + profile.profileId()
            ));
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":" + WOW_CHARACTER_CALLBACK));
        send(chatId, CHOOSE_PROFILE_PREFIX + action.label() + ":", inlineKeyboard(buttons));
    }

    private void runCharacterReport(long chatId, String actionKey, String profileIdValue) {
        CharacterReportAction action = CharacterReportAction.fromKey(actionKey);
        if (action != null && ALL_PROFILES_CALLBACK.equals(profileIdValue)) {
            sendAllCharacterReports(chatId, action);
            return;
        }
        TrackedPlayer profile = findActiveProfile(profileIdValue);
        if (action == null || profile == null) {
            send(chatId, "That character or report is no longer available. Use /wow to refresh the menu.");
            return;
        }
        switch (action) {
            case RAIDER_IO -> send(chatId, formatRaiderIoScore(raiderIoClient.getCurrentMPlusScore(
                    profile.region(), profile.realm(), profile.name()
            )));
            case ITEM_LEVEL -> send(chatId, formatItemLevel(raiderIoClient.getCurrentMPlusScore(
                    profile.region(), profile.realm(), profile.name()
            )));
            case MOUNTS -> send(chatId, "Insurmountable Collection: " + formatMountAchievementProgress(
                    mountService.getMountProgress(profile.realm(), profile.name()).usable()
            ));
        }
    }

    private void sendAllCharacterReports(long chatId, CharacterReportAction action) {
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        if (profiles.isEmpty()) {
            send(chatId, NO_ACTIVE_PROFILES_MESSAGE);
            return;
        }
        List<CharacterReportRow> rows = profiles.stream()
                .map(profile -> loadCharacterReportRow(action, profile))
                .sorted(characterReportComparator(action))
                .toList();
        StringBuilder message = new StringBuilder(action.label()).append(" — all profiles\n\n");
        for (CharacterReportRow row : rows) {
            appendCharacterReport(message, action, row);
        }
        send(chatId, message.toString().trim());
    }

    private CharacterReportRow loadCharacterReportRow(CharacterReportAction action, TrackedPlayer profile) {
        try {
            return switch (action) {
                case RAIDER_IO, ITEM_LEVEL -> new CharacterReportRow(
                        profile,
                        raiderIoClient.getCurrentMPlusScore(profile.region(), profile.realm(), profile.name()),
                        null
                );
                case MOUNTS -> new CharacterReportRow(
                        profile,
                        null,
                        mountService.getMountProgress(profile.realm(), profile.name())
                );
            };
        } catch (RuntimeException _) {
            return new CharacterReportRow(profile, null, null);
        }
    }

    private static Comparator<CharacterReportRow> characterReportComparator(CharacterReportAction action) {
        return Comparator
                .comparing(
                        (CharacterReportRow row) -> row.metric(action),
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparing(row -> row.profile().profileName(), String.CASE_INSENSITIVE_ORDER);
    }

    private void appendCharacterReport(
            StringBuilder message,
            CharacterReportAction action,
            CharacterReportRow row
    ) {
        TrackedPlayer profile = row.profile();
        if (row.unavailable()) {
            message.append("• ").append(profile.profileName()).append(" (")
                    .append(profile.name()).append('-').append(profile.realm())
                    .append(")\n  Status: unavailable\n\n");
            return;
        }
        switch (action) {
            case RAIDER_IO -> {
                RaiderIoClient.RaiderIoScore score = row.score();
                message.append("• ").append(profile.profileName()).append(" (")
                        .append(score.name()).append('-').append(score.realm()).append(")\n")
                        .append("  ").append(ITEM_LEVEL_PREFIX)
                        .append(valueOrUnavailable(score.itemLevel())).append('\n')
                        .append("  Score: ").append(valueOrUnavailable(score.all())).append('\n')
                        .append("  DPS: ").append(valueOrUnavailable(score.dps())).append('\n')
                        .append("  Healer: ").append(valueOrUnavailable(score.healer())).append('\n')
                        .append("  Tank: ").append(valueOrUnavailable(score.tank())).append("\n\n");
            }
            case ITEM_LEVEL -> {
                RaiderIoClient.RaiderIoScore score = row.score();
                message.append("• ").append(profile.profileName()).append(" (")
                        .append(score.name()).append('-').append(score.realm()).append(")\n")
                        .append("  ").append(ITEM_LEVEL_PREFIX)
                        .append(valueOrUnavailable(score.itemLevel())).append("\n\n");
            }
            case MOUNTS -> {
                BlizzardMountService.MountProgress progress = row.mountProgress();
                message.append("• ").append(profile.profileName()).append(" (")
                        .append(progress.characterName()).append('-').append(progress.realmSlug()).append(")\n")
                        .append("  Usable mounts: ").append(progress.usable()).append('\n')
                        .append("  Collected mounts: ").append(progress.collected()).append('\n')
                        .append("  Achievement: ").append(formatMountAchievementProgress(progress.usable()))
                        .append("\n\n");
            }
        }
    }

    private void sendWowAdminMenu(long chatId, Long senderUserId) {
        if (!isAdmin(senderUserId)) {
            send(chatId, adminOnlyMessage());
            return;
        }
        send(chatId, "Choose an admin action:", inlineKeyboard(List.of(
                adminButton("Users", ADMIN_USERS_CALLBACK),
                adminButton("User Access", ADMIN_USER_ACCESS_CALLBACK),
                adminButton(PROFILES_LABEL, WOW_PROFILES_CALLBACK),
                adminButton("Profile Access", ADMIN_PROFILE_ACCESS_CALLBACK),
                adminButton("Manage Alts", ADMIN_ALTS_CALLBACK),
                adminCommandButton("M+ Status", "mplus_status"),
                adminCommandButton("Vault Reminder", "vault_reminder"),
                adminCommandButton("Travel Import", "travel_import"),
                adminCommandButton("Import Status", "import_status"),
                adminCommandButton(HOUSING_TOP_LABEL, HOUSING_TOP_ACTION),
                adminCommandButton("Group ID", "group_id"),
                inlineButton("Public WoW Menu", WOW_ADMIN_CALLBACK_PREFIX + ADMIN_PUBLIC_CALLBACK)
        )));
    }

    private static InlineKeyboardButton adminButton(String label, String action) {
        return inlineButton(label, WOW_ADMIN_CALLBACK_PREFIX + action);
    }

    private static InlineKeyboardButton adminCommandButton(String label, String action) {
        return inlineButton(label, WOW_ADMIN_CALLBACK_PREFIX + WOW_COMMAND_CALLBACK + ":" + action);
    }

    private void routeWowAdminCallback(long chatId, long senderUserId, String callbackData) {
        if (!isAdmin(senderUserId)) {
            send(chatId, adminOnlyMessage());
            return;
        }
        String[] parts = callbackData.split(":");
        boolean routed = switch (parts.length) {
            case 2 -> routeAdminMenuCallback(chatId, senderUserId, parts[1]);
            case 3 -> routeAdminActionCallback(chatId, senderUserId, parts[1], parts[2]);
            case 4 -> routeAdminAccessCallback(chatId, parts[1], parts[2], parts[3]);
            case 5 -> routeAdminAltCallback(chatId, parts[1], parts[2], parts[3], parts[4]);
            default -> false;
        };
        if (!routed) {
            send(chatId, ADMIN_INVALID_SELECTION_MESSAGE);
        }
    }

    private boolean routeAdminMenuCallback(long chatId, long senderUserId, String action) {
        return switch (action) {
            case WOW_MENU_CALLBACK -> handled(() -> sendWowAdminMenu(chatId, senderUserId));
            case ADMIN_PUBLIC_CALLBACK -> handled(() -> sendWowMenu(chatId));
            case ADMIN_USERS_CALLBACK -> handled(() -> send(
                    chatId,
                    formatTelegramUsers(),
                    adminBackKeyboard()
            ));
            case ADMIN_USER_ACCESS_CALLBACK -> handled(() -> sendAdminUserAccessMenu(chatId));
            case WOW_PROFILES_CALLBACK -> handled(() -> send(
                    chatId,
                    formatPlayerProfiles(),
                    adminBackKeyboard()
            ));
            case ADMIN_PROFILE_ACCESS_CALLBACK -> handled(() -> sendAdminProfileAccessMenu(chatId));
            case ADMIN_ALTS_CALLBACK -> handled(() -> sendAdminAltProfileMenu(chatId));
            default -> false;
        };
    }

    private boolean routeAdminActionCallback(
            long chatId,
            long senderUserId,
            String action,
            String value
    ) {
        return switch (action) {
            case ADMIN_ALTS_CALLBACK -> handled(() -> sendAdminAltManagementMenu(chatId, value, null));
            case ADMIN_ALT_ADD_CALLBACK -> handled(() -> startAltAddition(chatId, senderUserId, value));
            case ADMIN_HOUSING_CALLBACK -> routeHousingRankingCallback(chatId, value);
            case WOW_COMMAND_CALLBACK -> handled(() -> runWowAdminAction(chatId, senderUserId, value));
            default -> false;
        };
    }

    private boolean routeAdminAccessCallback(long chatId, String action, String id, String active) {
        return switch (action) {
            case ADMIN_USER_CALLBACK -> handled(() -> changeUserAccessFromButton(chatId, id, active));
            case ADMIN_PROFILE_CALLBACK -> handled(() -> changeProfileAccessFromButton(chatId, id, active));
            default -> false;
        };
    }

    private boolean routeAdminAltCallback(
            long chatId,
            String action,
            String profileId,
            String characterId,
            String active
    ) {
        if (!ADMIN_ALT_CALLBACK.equals(action)) {
            return false;
        }
        changeAltAccessFromButton(chatId, profileId, characterId, active);
        return true;
    }

    private InlineKeyboardMarkup adminBackKeyboard() {
        return inlineKeyboard(List.of(inlineButton(BACK_LABEL, WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK)));
    }

    private InlineKeyboardMarkup housingRankingKeyboard() {
        return inlineKeyboard(List.of(
                housingRankingButton(HOUSING_SALES_LABEL, HousingRanking.SALES),
                housingRankingButton(HOUSING_AVERAGE_LABEL, HousingRanking.AVERAGE_PRICE),
                housingRankingButton(HOUSING_PRICE_LABEL, HousingRanking.MARKET_PRICE),
                inlineButton(BACK_LABEL, WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        ));
    }

    private static InlineKeyboardButton housingRankingButton(String label, HousingRanking ranking) {
        return inlineButton(
                label,
                WOW_ADMIN_CALLBACK_PREFIX + ADMIN_HOUSING_CALLBACK + ":" + ranking.key()
        );
    }

    private boolean routeHousingRankingCallback(long chatId, String rankingKey) {
        HousingRanking ranking = HousingRanking.fromKey(rankingKey);
        if (ranking == null) {
            return false;
        }
        sendHousingReport(chatId, ranking);
        return true;
    }

    private void runWowAdminAction(long chatId, long senderUserId, String actionKey) {
        switch (actionKey) {
            case "mplus_status" -> send(chatId, adminMPlusStatus(senderUserId), adminBackKeyboard());
            case "vault_reminder" -> send(chatId, vaultReminderService.checkNowMessage(), adminBackKeyboard());
            case "travel_import" -> send(chatId, timeToGoCommands.submitHistoricalImport(), adminBackKeyboard());
            case "import_status" -> send(chatId, timeToGoCommands.refreshHistoricalImport(), adminBackKeyboard());
            case HOUSING_TOP_ACTION -> sendHousingTop(chatId);
            case "group_id" -> send(chatId, "Chat ID: " + chatId, adminBackKeyboard());
            default -> send(chatId, "That admin action is unavailable. Use /wow_admin to refresh the menu.");
        }
    }

    private void sendAdminUserAccessMenu(long chatId) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (var user : telegramBotUserService.users()) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            boolean enable = !user.isActive();
            String displayName = user.getDisplayName() == null || user.getDisplayName().isBlank()
                    ? Long.toString(user.getTelegramUserId())
                    : user.getDisplayName();
            buttons.add(inlineButton(
                    accessLabel(enable, displayName),
                    WOW_ADMIN_CALLBACK_PREFIX + "user:" + user.getTelegramUserId() + ":" + enable
            ));
        }
        if (buttons.isEmpty()) {
            send(chatId, "No Telegram users are registered.", adminBackKeyboard());
            return;
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
        send(chatId, "Choose a user access change:", inlineKeyboard(buttons));
    }

    private void changeUserAccessFromButton(long chatId, String userIdValue, String activeValue) {
        Long userId = parseLong(userIdValue);
        Boolean active = parseBoolean(activeValue);
        if (userId == null || userId <= 0 || active == null) {
            send(chatId, "That user access selection is invalid. Use /wow_admin to refresh the menu.");
            return;
        }
        telegramBotUserService.setActive(userId, active);
        telegramAccessPolicy.userAccessChanged(userId);
        send(chatId, TELEGRAM_USER_PREFIX + userId + statusSuffix(active), adminBackKeyboard());
    }

    private void sendAdminProfileAccessMenu(long chatId) {
        List<TrackedPlayerService.PlayerProfile> profiles = trackedPlayerService.profiles();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (var profile : profiles) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            boolean enable = !profile.active();
            buttons.add(inlineButton(
                    accessLabel(enable, profile.name()),
                    WOW_ADMIN_CALLBACK_PREFIX + "profile:" + profile.id() + ":" + enable
            ));
        }
        if (buttons.isEmpty()) {
            send(chatId, NO_PLAYER_PROFILES_MESSAGE, adminBackKeyboard());
            return;
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
        send(chatId, "Choose a profile access change:", inlineKeyboard(buttons));
    }

    private void changeProfileAccessFromButton(long chatId, String profileIdValue, String activeValue) {
        Long profileId = parseLong(profileIdValue);
        Boolean active = parseBoolean(activeValue);
        if (profileId == null || profileId <= 0 || active == null) {
            send(chatId, "That profile access selection is invalid. Use /wow_admin to refresh the menu.");
            return;
        }
        var profile = trackedPlayerService.profiles().stream()
                .filter(candidate -> candidate.id() == profileId)
                .findFirst()
                .orElse(null);
        if (profile == null) {
            send(chatId, ADMIN_PROFILE_MISSING_MESSAGE);
            return;
        }
        trackedPlayerService.setProfileActive(profile.id(), active);
        send(chatId, PROFILE_PREFIX + profile.name() + statusSuffix(active), adminBackKeyboard());
    }

    private void sendAdminAltProfileMenu(long chatId) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (var profile : trackedPlayerService.profiles()) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            buttons.add(inlineButton(
                    profile.name(),
                    WOW_ADMIN_CALLBACK_PREFIX + ADMIN_ALTS_CALLBACK + ":" + profile.id()
            ));
        }
        if (buttons.isEmpty()) {
            send(chatId, NO_PLAYER_PROFILES_MESSAGE, adminBackKeyboard());
            return;
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
        send(chatId, "Choose a profile to manage its alts:", inlineKeyboard(buttons));
    }

    private void sendAdminAltManagementMenu(long chatId, String profileIdValue, String statusMessage) {
        Long profileId = parseLong(profileIdValue);
        var profile = findProfile(profileId);
        if (profile == null) {
            send(chatId, ADMIN_PROFILE_MISSING_MESSAGE);
            return;
        }

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(inlineButton(
                "Add Alt",
                WOW_ADMIN_CALLBACK_PREFIX + ADMIN_ALT_ADD_CALLBACK + ":" + profile.id()
        ));
        for (var character : profile.characters()) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS || character.selected()) {
                continue;
            }
            boolean enable = !character.active();
            buttons.add(inlineButton(
                    accessLabel(enable, character.name() + "-" + character.realm()),
                    WOW_ADMIN_CALLBACK_PREFIX + ADMIN_ALT_CALLBACK + ":" + profile.id() + ":"
                            + character.id() + ":" + enable
            ));
        }
        buttons.add(inlineButton(BACK_LABEL, WOW_ADMIN_CALLBACK_PREFIX + ADMIN_ALTS_CALLBACK));
        String prompt = statusMessage == null
                ? "Manage alts for " + profile.name() + ":"
                : statusMessage + "\n\nManage alts for " + profile.name() + ":";
        send(chatId, prompt, inlineKeyboard(buttons));
    }

    private void changeAltAccessFromButton(
            long chatId,
            String profileIdValue,
            String characterIdValue,
            String activeValue
    ) {
        Long profileId = parseLong(profileIdValue);
        Long characterId = parseLong(characterIdValue);
        Boolean active = parseBoolean(activeValue);
        var profile = findProfile(profileId);
        var character = profile == null || characterId == null
                ? null
                : profile.characters().stream()
                        .filter(candidate -> candidate.id() == characterId)
                        .findFirst()
                        .orElse(null);
        if (profile == null || character == null || character.selected() || active == null) {
            send(chatId, "That alt selection is invalid. Use /wow_admin to refresh the menu.");
            return;
        }
        try {
            trackedPlayerService.setCharacterActive(profile.id(), character.id(), active);
            sendAdminAltManagementMenu(
                    chatId,
                    profileIdValue,
                    character.name() + (active
                            ? ENABLED_STATUS_SUFFIX
                            : " disabled and hidden from the owner’s alt list.")
            );
        } catch (IllegalArgumentException e) {
            send(chatId, COULD_NOT_UPDATE_PREFIX + "alt: " + e.getMessage(), adminBackKeyboard());
        }
    }

    private void startAltAddition(long chatId, long senderUserId, String profileIdValue) {
        Long profileId = parseLong(profileIdValue);
        var profile = findProfile(profileId);
        if (profile == null) {
            send(chatId, ADMIN_PROFILE_MISSING_MESSAGE);
            return;
        }
        pendingAltAdditions.put(
                new PendingAltKey(chatId, senderUserId),
                new PendingAltAddition(profile.id(), profile.name(), Instant.now().plus(ALT_ADDITION_TTL))
        );
        sendAltAdditionPrompt(chatId, profile.name(), "Enter the EU realm and character name.");
    }

    private boolean handlePendingAltAddition(Update update) {
        if (!update.getMessage().hasText() || update.getMessage().getFrom() == null) {
            return false;
        }
        long chatId = update.getMessage().getChatId();
        long senderUserId = update.getMessage().getFrom().getId();
        PendingAltKey key = new PendingAltKey(chatId, senderUserId);
        PendingAltAddition pending = pendingAltAdditions.get(key);
        if (pending == null) {
            return false;
        }

        String text = update.getMessage().getText().trim();
        if (text.startsWith("/")) {
            pendingAltAdditions.remove(key);
            return false;
        }
        if (!isAdmin(senderUserId) || !telegramAccessPolicy.isAllowed(chatId, senderUserId)) {
            pendingAltAdditions.remove(key);
            return true;
        }
        if (Instant.now().isAfter(pending.expiresAt())) {
            pendingAltAdditions.remove(key);
            send(chatId, "Alt addition expired. Open /wow_admin and try again.");
            return true;
        }

        String[] parts = text.split("\\s+");
        if (parts.length != 2) {
            sendAltAdditionPrompt(chatId, pending.profileName(), "Use exactly: realm character-name");
            return true;
        }
        try {
            trackedPlayerService.addCharacter(pending.profileId(), parts[0], parts[1]);
            pendingAltAdditions.remove(key);
            send(chatId, parts[1] + "-" + parts[0] + " added to profile " + pending.profileName() + ".",
                    inlineKeyboard(List.of(inlineButton(
                            "Manage Alts",
                            WOW_ADMIN_CALLBACK_PREFIX + ADMIN_ALTS_CALLBACK + ":" + pending.profileId()
                    ))));
        } catch (IllegalArgumentException e) {
            sendAltAdditionPrompt(chatId, pending.profileName(), "Could not add alt: " + e.getMessage());
        }
        return true;
    }

    private void sendAltAdditionPrompt(long chatId, String profileName, String instruction) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(instruction + "\nExample: stormscale Thelinqq\nProfile: " + profileName)
                    .build();
            message.setReplyMarkup(ForceReplyKeyboard.builder()
                    .forceReply(true)
                    .selective(true)
                    .inputFieldPlaceholder("realm character-name")
                    .build());
            client.execute(message);
        } catch (Exception ignored) {
            // Delivery failures are isolated so Telegram polling can continue processing later updates.
        }
    }

    private TrackedPlayerService.PlayerProfile findProfile(Long profileId) {
        if (profileId == null || profileId <= 0) {
            return null;
        }
        return trackedPlayerService.profiles().stream()
                .filter(candidate -> candidate.id() == profileId)
                .findFirst()
                .orElse(null);
    }

    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    private static String accessLabel(boolean enable, String subject) {
        return (enable ? ENABLE_LABEL_PREFIX : DISABLE_LABEL_PREFIX) + subject;
    }

    private static String statusSuffix(boolean active) {
        return active ? ENABLED_STATUS_SUFFIX : DISABLED_STATUS_SUFFIX;
    }

    private void sendOwnProfile(CommandContext context) {
        Long telegramUserId = context.senderUserId();
        if (telegramUserId == null) {
            send(context.chatId(), TELEGRAM_USER_UNAVAILABLE);
            return;
        }
        send(context.chatId(), trackedPlayerService.profileForTelegramUser(telegramUserId)
                .map(BlackBoxBot::formatOwnPlayerProfile)
                .orElse("No player profile is linked to your Telegram account. Ask the bot admin to link it."));
    }

    private void changeOwnMain(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 3) {
            send(context.chatId(), """
                    Usage: /profile_main <realm> <character>
                    Example: /profile_main stormscale Alicemage
                    """.strip());
            return;
        }
        Long telegramUserId = context.senderUserId();
        if (telegramUserId == null) {
            send(context.chatId(), TELEGRAM_USER_UNAVAILABLE);
            return;
        }

        try {
            trackedPlayerService.switchOwnedCharacter(telegramUserId, parts[1], parts[2]);
            send(context.chatId(), "Your selected main is now " + parts[2] + "-" + parts[1] + " (EU).");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not change your main: " + e.getMessage());
        }
    }

    private boolean handleProfileAdministrationCommand(CommandContext context) {
        Runnable adminAction = switch (context.command()) {
            case "/profilelist" -> () -> send(context.chatId(), formatPlayerProfiles());
            case "/profileadd" -> () -> addProfile(context);
            case "/profilecharadd" -> () -> addProfileCharacter(context);
            case "/profilechardelete", "/profilecharremove" -> () -> deleteProfileCharacter(context);
            case "/profilelink" -> () -> linkProfile(context);
            case "/profileunlink" -> () -> unlinkProfile(context);
            case "/profileswitch" -> () -> switchProfileCharacter(context);
            case "/profiledisable", "/profileenable" -> () -> changeProfileStatus(context);
            case "/vaultremindernow" -> () -> send(context.chatId(), vaultReminderService.checkNowMessage());
            default -> null;
        };
        if (adminAction == null) {
            return false;
        }
        runAdminCommand(context, adminAction);
        return true;
    }

    private void runAdminCommand(CommandContext context, Runnable action) {
        if (isAdmin(context.senderUserId())) {
            action.run();
        } else {
            send(context.chatId(), adminOnlyMessage());
        }
    }

    private void addProfile(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 5) {
            send(context.chatId(), """
                    Usage: /profile_add <profile> <region> <realm> <character>
                    Example: /profile_add Alice eu stormscale Alicechar
                    """.strip());
            return;
        }
        try {
            trackedPlayerService.addProfile(parts[1], parts[2], parts[3], parts[4]);
            send(context.chatId(), PROFILE_PREFIX + parts[1] + " added with selected character " + parts[4] + ".");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not add profile: " + e.getMessage());
        }
    }

    private void addProfileCharacter(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 4) {
            send(context.chatId(), """
                    Usage: /profile_char_add <profile> <realm> <character>
                    Example: /profile_char_add Alice stormscale Alicemage
                    """.strip());
            return;
        }
        try {
            trackedPlayerService.addCharacter(parts[1], parts[2], parts[3]);
            send(context.chatId(), parts[3] + "-" + parts[2] + " added to profile " + parts[1] + ".");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not add character: " + e.getMessage());
        }
    }

    private void deleteProfileCharacter(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 4) {
            send(context.chatId(), """
                    Usage: /profile_char_delete <profile> <realm> <character>
                    Example: /profile_char_delete Alice stormscale Alicealt
                    """.strip());
            return;
        }
        try {
            trackedPlayerService.deleteCharacter(parts[1], parts[2], parts[3]);
            send(context.chatId(), "Character removed from the profile.");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not delete character: " + e.getMessage());
        }
    }

    private void linkProfile(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 3) {
            send(context.chatId(), """
                    Usage: /profile_link <telegramUserId> <profile>
                    Example: /profile_link 123456789 Alice
                    """.strip());
            return;
        }
        Long telegramUserId = parseLong(parts[1]);
        if (telegramUserId == null || telegramUserId <= 0) {
            send(context.chatId(), "Telegram user ID must be a positive number.");
            return;
        }
        try {
            trackedPlayerService.linkProfile(telegramUserId, parts[2]);
            send(context.chatId(), PROFILE_PREFIX + parts[2] + " linked to Telegram user " + telegramUserId + ".");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not link profile: " + e.getMessage());
        }
    }

    private void unlinkProfile(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 2) {
            send(context.chatId(), "Usage: /profile_unlink <profile>");
            return;
        }
        try {
            trackedPlayerService.unlinkProfile(parts[1]);
            send(context.chatId(), PROFILE_PREFIX + parts[1] + " is no longer linked to a Telegram user.");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not unlink profile: " + e.getMessage());
        }
    }

    private void switchProfileCharacter(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 5) {
            send(context.chatId(), """
                    Usage: /profile_switch <profile> <region> <realm> <character>
                    Example: /profile_switch Alice eu tarren-mill Alicealt
                    """.strip());
            return;
        }
        try {
            trackedPlayerService.switchCharacter(parts[1], parts[2], parts[3], parts[4]);
            send(context.chatId(), PROFILE_PREFIX + parts[1] + " now uses " + parts[4]
                    + ". The previous character was kept as an alt.");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not switch character: " + e.getMessage());
        }
    }

    private void changeProfileStatus(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        boolean active = context.command().equals("/profileenable");
        if (parts.length != 2) {
            send(context.chatId(), USAGE_PREFIX + (active ? "/profile_enable" : "/profile_disable")
                    + " <profile>");
            return;
        }
        try {
            trackedPlayerService.setProfileActive(parts[1], active);
            send(context.chatId(), PROFILE_PREFIX + parts[1] + statusSuffix(active));
        } catch (IllegalArgumentException e) {
            send(context.chatId(), COULD_NOT_UPDATE_PREFIX + "profile: " + e.getMessage());
        }
    }

    private boolean handleWarcraftInformationCommand(CommandContext context) {
        return switch (context.command()) {
            case "/affixes" -> handled(() -> send(
                    context.chatId(),
                    AffixFormatter.formatWeeklyAffixes(raiderIoClient.getWeeklyAffixes("eu", "en"))
            ));
            case "/guild" -> handled(() -> handleGuildCommand(context));
            case "/guildlist" -> handled(() -> send(context.chatId(), formatAvailableRaids()));
            case "/rwf" -> handled(() -> sendRaceToWorldFirstStandings(context.chatId()));
            case "/raidprogress" -> handled(() -> send(
                    context.chatId(),
                    raidReportService.progress(raidReportPlayersByName(commandArguments(context)))
            ));
            case "/raidvault" -> handled(() -> send(
                    context.chatId(),
                    raidReportService.weeklyVault(raidReportPlayersByName(commandArguments(context)))
            ));
            case "/raidcombat" -> handled(() -> send(
                    context.chatId(),
                    warcraftLogsStatisticsService.raidCombatMessage(raidReportPlayersByName(commandArguments(context)))
            ));
            default -> false;
        };
    }

    private List<TrackedPlayer> raidReportPlayersByName(String profileArgument) {
        String requestedProfile = profileArgument == null ? "" : profileArgument.trim();
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        if (requestedProfile.isBlank() || ALL_PROFILES_CALLBACK.equalsIgnoreCase(requestedProfile)) {
            return profiles;
        }
        return profiles.stream()
                .filter(profile -> profile.profileName().equalsIgnoreCase(requestedProfile))
                .toList();
    }

    private void sendRaceToWorldFirstStandings(long chatId) {
        try {
            send(chatId, raceToWorldFirstService.currentStandingsMessage());
        } catch (RuntimeException _) {
            send(chatId, "Could not fetch the Race to World First standings from Raider.IO.");
        }
    }

    private void handleGuildCommand(CommandContext context) {
        String argument = commandArguments(context);
        JsonNode guild = fetchDefaultGuild();
        if (argument.equalsIgnoreCase("list")) {
            send(context.chatId(), formatRaidProgressionList(guild));
            return;
        }

        String raidKey = selectRaidKey(argument, guild);
        String lastCrawledAt = guild.path("last_crawled_at").asText("n/a");
        send(context.chatId(), """
                %s
                %s
                Last update: %s
                """.formatted(
                defaultGuildProps.guildName(),
                RaidProgressFormatter.formatRaidLine(guild, raidKey),
                formatLastCrawled(lastCrawledAt)
        ).strip());
    }

    private JsonNode fetchDefaultGuild() {
        return raiderIoClient.getGuildProfile(
                defaultGuildProps.region(),
                defaultGuildProps.realm(),
                defaultGuildProps.guildName()
        );
    }

    private String selectRaidKey(String argument, JsonNode guild) {
        if (!argument.isBlank()) {
            return argument;
        }
        String configuredRaid = defaultGuildProps.raidName();
        return configuredRaid == null || configuredRaid.isBlank()
                ? RaidPicker.pickBestRaidKey(guild)
                : configuredRaid;
    }

    private static String formatRaidProgressionList(JsonNode guild) {
        StringBuilder message = new StringBuilder("Raids:\n");
        guild.path("raid_progression").fieldNames().forEachRemaining(raidKey -> message
                .append("• ")
                .append(RaidProgressFormatter.formatRaidLine(guild, raidKey))
                .append("\n"));
        return message.append("\nUse: /guild <raidKey>").toString();
    }

    private String formatAvailableRaids() {
        JsonNode guild = fetchDefaultGuild();
        StringBuilder message = new StringBuilder("Available raids:\n");
        guild.path("raid_progression").fieldNames().forEachRemaining(raidKey -> message
                .append("• ")
                .append(raidKey)
                .append("\n"));
        return message.toString();
    }

    private boolean handleEconomyCommand(CommandContext context) {
        return switch (context.command()) {
            case "/price" -> handled(() -> handlePrice(context));
            case "/priceah" -> handled(() -> handleAuctionHousePrice(context));
            case "/token" -> handled(() -> handleTokenPrice(context.chatId()));
            case TOKEN_LOWEST_WEEK_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    context,
                    TokenPriceExtreme.LOWEST,
                    TokenHistoryPeriod.WEEK
            ));
            case TOKEN_LOWEST_MONTH_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    context,
                    TokenPriceExtreme.LOWEST,
                    TokenHistoryPeriod.MONTH
            ));
            case TOKEN_HIGHEST_WEEK_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    context,
                    TokenPriceExtreme.HIGHEST,
                    TokenHistoryPeriod.WEEK
            ));
            case TOKEN_HIGHEST_MONTH_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    context,
                    TokenPriceExtreme.HIGHEST,
                    TokenHistoryPeriod.MONTH
            ));
            case TOKEN_BEST_COMMAND -> handled(() -> handleBestTokenTradingHours(context));
            // Compatibility aliases for previously published commands.
            case "/tokenlowest" -> handled(() -> handleTokenPriceExtreme(context, TokenPriceExtreme.LOWEST));
            case "/tokenhighest" -> handled(() -> handleTokenPriceExtreme(context, TokenPriceExtreme.HIGHEST));
            case "/tokenbest" -> handled(() -> handleBestTokenTradingHours(context));
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
            send(context.chatId(), """
                    Usage: /price <itemId|item name> [realm-if-itemId]
                    Examples:
                    /price 72092 Draenor
                    /price wow token
                    """.strip());
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
            send(chatId, formatPriceMessage(WOW_TOKEN_EU_SCOPE, itemName, result));
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
        String scope = isWowToken(item.name()) ? WOW_TOKEN_EU_SCOPE : "Region avg (EU)";
        send(chatId, formatPriceMessage(scope, item.name(), result));
    }

    private void handleAuctionHousePrice(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length != 4) {
            send(context.chatId(), """
                    Usage: /price_ah <connectedRealmId> <auctionHouseId> <itemId>
                    Example: /price_ah 1080 2 72092
                    """.strip());
            return;
        }

        Long connectedRealmId = parseLong(parts[1]);
        Long auctionHouseId = parseLong(parts[2]);
        Long itemId = parseLong(parts[3]);
        if (connectedRealmId == null || auctionHouseId == null || itemId == null) {
            send(context.chatId(), "Invalid numbers. Example: /price_ah 1080 2 72092");
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
        send(chatId, wowTokenReportService.currentPrice());
    }

    private void handleTokenPriceExtreme(CommandContext context, TokenPriceExtreme extreme) {
        String requestedPeriod = commandArguments(context).toLowerCase(Locale.ROOT);
        switch (requestedPeriod) {
            case "week" -> handleTokenPriceExtreme(context, extreme, TokenHistoryPeriod.WEEK);
            case "month" -> handleTokenPriceExtreme(context, extreme, TokenHistoryPeriod.MONTH);
            default -> {
                send(context.chatId(), extreme.usageMessage());
            }
        }
    }

    private void handleTokenPriceExtreme(
            CommandContext context,
            TokenPriceExtreme extreme,
            TokenHistoryPeriod period
    ) {
        if (!commandArguments(context).isBlank() && context.command().contains("_")) {
            send(context.chatId(), USAGE_PREFIX + extreme.commandFor(period));
            return;
        }

        String report = extreme == TokenPriceExtreme.LOWEST
                ? wowTokenReportService.lowestPrice(period.lookback(), period.label())
                : wowTokenReportService.highestPrice(period.lookback(), period.label());
        send(context.chatId(), report);
    }

    private void handleBestTokenTradingHours(CommandContext context) {
        if (!commandArguments(context).isBlank()) {
            send(context.chatId(), USAGE_PREFIX + TOKEN_BEST_COMMAND);
            return;
        }
        send(context.chatId(), wowTokenReportService.bestTradingHours());
    }

    private void handleMountAchievement(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length < 3) {
            send(context.chatId(), """
                    Usage: /mount_achievement <realm> <name>
                    Example: /mount_achievement stormscale bucothered
                    """.strip());
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
        String[] commandAndArguments = context.text().split("\\s+", 2);
        return commandAndArguments.length == 2 ? commandAndArguments[1].trim() : "";
    }

    private static boolean handled(Runnable action) {
        action.run();
        return true;
    }

    private boolean handleSeasonCommand(CommandContext context) {
        long chatId = context.chatId();
        String cmd = context.command();

        if (cmd.equals("/title") || cmd.equals("/titlewatch")) {
            send(chatId, mplusTitleWatchService.onePercentReport());
            return true;
        }

        if (cmd.equals("/title01") || cmd.equals("/title0.1") || cmd.equals("/title001")) {
            send(chatId, mplusTitleWatchService.pointOnePercentReport());
            return true;
        }

        if (cmd.equals("/seasonrecap") || cmd.equals("/recap")) {
            send(chatId, mplusSeasonReportService.combinedRecap());
            return true;
        }

        if (cmd.equals("/seasonrecapdepleted")) {
            send(chatId, mplusSeasonReportService.depletedRecap());
            return true;
        }

        if (cmd.equals("/seasonrecapabandoned")) {
            send(chatId, mplusSeasonReportService.abandonedRecap());
            return true;
        }

        return false;
    }

    private boolean handleTravelCommand(CommandContext context) {
        return switch (context.command()) {
            case ROAD_COMMAND, "/travel", "/timetogo" -> handled(() -> send(
                    context.chatId(),
                    timeToGoCommands.formatCurrent(context.text())
            ));
            case ROAD_BEST_COMMAND, "/travelbest", "/timetogobest" -> handled(() -> send(
                    context.chatId(),
                    timeToGoCommands.formatBest(context.text())
            ));
            case "/timetogoimport30", "/roadimport30", "/travelimport30" -> handled(() ->
                    runAdminCommand(context, () -> submitHistoricalImport(context.chatId())));
            case "/timetogoimportstatus", "/roadimportstatus", "/travelimportstatus" -> handled(() ->
                    runAdminCommand(context, () -> refreshHistoricalImport(context.chatId())));
            default -> false;
        };
    }

    private void submitHistoricalImport(long chatId) {
        try {
            send(chatId, timeToGoCommands.submitHistoricalImport());
        } catch (Exception e) {
            send(chatId, "TomTom historical import submit failed: " + e.getMessage());
        }
    }

    private void refreshHistoricalImport(long chatId) {
        try {
            send(chatId, timeToGoCommands.refreshHistoricalImport());
        } catch (Exception e) {
            send(chatId, "TomTom historical import status failed: " + e.getMessage());
        }
    }

    private boolean handleGeneralCommand(CommandContext context) {
        return switch (context.command()) {
            case "/wow", "/start" -> handled(() -> sendWowMenu(context.chatId()));
            case "/wowadmin" -> handled(() -> sendWowAdminMenu(context.chatId(), context.senderUserId()));
            case "/vault" -> handled(() -> handleVaultCommand(context));
            case "/rio" -> handled(() -> handleRaiderIoCommand(context));
            case "/help", "/commands" -> handled(() -> sendWowMenu(context.chatId()));
            case "/help-admin" -> handled(() -> runAdminCommand(
                    context,
                    () -> sendWowAdminMenu(context.chatId(), context.senderUserId())
            ));
            default -> false;
        };
    }

    private void handleVaultCommand(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length == 1) {
            send(context.chatId(), mplusSeasonReportService.weeklyVaultWatch());
            return;
        }
        if (parts.length != 3 && parts.length != 4) {
            send(context.chatId(), "Usage: /vault\nOptional Mythic+ lookup: /vault <realm> <name>");
            return;
        }

        String region = parts.length == 4 ? parts[1].toLowerCase(Locale.ROOT) : "eu";
        String realm = parts.length == 4 ? parts[2] : parts[1];
        String name = parts.length == 4 ? parts[3] : parts[2];
        send(context.chatId(), mplusSeasonReportService.weeklyVault(region, realm, name));
    }

    private void handleRaiderIoCommand(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        if (parts.length < 4) {
            send(context.chatId(), """
                    Usage: /rio <region> <realm> <name>
                    Example: /rio eu stormscale bucothered
                    """.strip());
            return;
        }

        String region = parts[1].toLowerCase(Locale.ROOT);
        String realm = parts[2];
        String name = parts[3];
        try {
            send(context.chatId(), formatRaiderIoScore(raiderIoClient.getCurrentMPlusScore(region, realm, name)));
        } catch (Exception e) {
            send(context.chatId(), """
                    Couldn’t fetch Raider.IO for %s/%s/%s
                    Reason: %s
                    """.formatted(region, realm, name, e.getMessage()).strip());
        }
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

    private String formatTelegramIdentity(Update update) {
        var user = update.getMessage().getFrom();
        if (user == null) {
            return TELEGRAM_USER_UNAVAILABLE;
        }

        String username = user.getUserName();
        return """
                Your Telegram user ID: %s
                Username: %s
                Chat ID: %s
                """.formatted(
                user.getId(),
                username == null || username.isBlank() ? "not set" : "@" + username,
                update.getMessage().getChatId()
        ).strip();
    }

    private String formatTelegramUsers() {
        var users = telegramBotUserService.users();
        if (users.isEmpty()) {
            return "No Telegram users are registered. Use /user_add <telegramUserId> [display name].";
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

    private boolean isAdmin(Long senderUserId) {
        return adminUserId > 0
                && senderUserId != null
                && senderUserId == adminUserId;
    }

    private String adminOnlyMessage() {
        if (adminUserId <= 0) {
            return "Profile management is disabled because TELEGRAM_ADMIN_USER_ID is not configured. "
                    + "Send /my_id, then add that numeric user ID to the server .env.";
        }
        return "This command can only be used by the configured bot administrator.";
    }

    private static String profileHelpMessage() {
        return """
                Player profile help

                View your registered characters:
                /profile

                Select one of your registered EU Retail characters as your main:
                /profile_main <realm> <character>
                Example: /profile_main stormscale Alicemage

                View all current group mains:
                /mains

                Only the characters registered to your profile can be selected. Ask the bot admin to add another character. /mplus_combat follows each profile's selected main.
                """.strip();
    }

    private static String formatOwnPlayerProfile(TrackedPlayerService.PlayerProfile profile) {
        StringBuilder message = new StringBuilder("Your player profile\n");
        appendPlayerProfile(message, profile, false);
        return message.toString().trim();
    }

    private String formatCurrentMains() {
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();
        if (players.isEmpty()) {
            return "No active group mains are configured.";
        }

        StringBuilder message = new StringBuilder("Current group mains\n");
        for (TrackedPlayer player : players) {
            message.append("\n• ").append(player.profileName())
                    .append(" — ").append(player.name())
                    .append("-").append(player.realm())
                    .append(" (").append(player.region().toUpperCase(Locale.ROOT)).append(")");
        }
        return message.toString();
    }

    private String formatPlayerProfiles() {
        var profiles = trackedPlayerService.profiles();
        if (profiles.isEmpty()) {
            return "No player profiles configured.";
        }

        StringBuilder sb = new StringBuilder("Player profiles\n");
        for (var profile : profiles) {
            appendPlayerProfile(sb, profile, true);
        }
        return sb.toString().trim();
    }

    private static void appendPlayerProfile(
            StringBuilder message,
            TrackedPlayerService.PlayerProfile profile,
            boolean showTelegramLink
    ) {
        message.append("\n• ").append(profile.name());
        if (!profile.active()) {
            message.append(" (disabled)");
        }
        if (showTelegramLink) {
            message.append(profile.telegramUserId() == null
                    ? " — Telegram: not linked"
                    : " — Telegram: " + profile.telegramUserId());
        }
        message.append("\n");
        for (var character : profile.characters()) {
            if (!showTelegramLink && !character.active()) {
                continue;
            }
            message.append(character.selected() ? "  → " : "    ")
                    .append(character.name())
                    .append("-").append(character.realm())
                    .append(" (").append(character.region().toUpperCase(Locale.ROOT)).append(")");
            if (!character.active()) {
                message.append(" [inactive]");
            }
            message.append("\n");
        }
    }

    private static String formatRaiderIoScore(RaiderIoClient.RaiderIoScore score) {
        String profile = score.profileUrl() == null || score.profileUrl().isBlank()
                ? ""
                : "%nProfile: %s".formatted(score.profileUrl());
        return """
                Raider.IO (current season)
                %s - %s (%s)
                %s%s
                Score: %s
                DPS: %s | Healer: %s | Tank: %s%s
                """.formatted(
                score.name(),
                score.realm(),
                score.region(),
                ITEM_LEVEL_PREFIX,
                valueOrUnavailable(score.itemLevel()),
                valueOrUnavailable(score.all()),
                valueOrUnavailable(score.dps()),
                valueOrUnavailable(score.healer()),
                valueOrUnavailable(score.tank()),
                profile
        ).strip();
    }

    private static String formatItemLevel(RaiderIoClient.RaiderIoScore score) {
        return """
                %s
                %s - %s (%s)
                Equipped item level: %s
                """.formatted(
                ITEM_LEVEL_LABEL,
                score.name(),
                score.realm(),
                score.region(),
                valueOrUnavailable(score.itemLevel())
        ).strip();
    }

    private static String valueOrUnavailable(BigDecimal value) {
        return value == null ? "n/a" : value.toString();
    }

    private void send(long chatId, String msg) {
        send(chatId, msg, null);
    }

    private void send(long chatId, String msg, InlineKeyboardMarkup keyboard) {
        try {
            SendMessage message = SendMessage.builder().chatId(chatId).text(msg).build();
            message.setReplyMarkup(keyboard);
            client.execute(message);
        } catch (Exception ignored) {
            // Delivery failures are isolated so Telegram polling can continue processing later updates.
        }
    }

    private void answerCallback(String callbackQueryId) {
        if (callbackQueryId == null || callbackQueryId.isBlank()) {
            return;
        }
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build());
        } catch (Exception ignored) {
            // A failed acknowledgement must not prevent the selected report from running.
        }
    }

    private void removeInlineKeyboard(long chatId, Integer messageId) {
        if (messageId == null) {
            return;
        }
        try {
            client.execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
        } catch (Exception ignored) {
            // A stale menu must not prevent the selected action from running.
        }
    }

    private static String formatMountLookupError(String realm, String name, Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("404")) {
            return """
                    Mount progression not found for %s on %s (EU).
                    Use: /mount_achievement <realm> <name>
                    Example: /mount_achievement stormscale bucothered
                    Also check that the character exists on EU and has logged out recently.
                    """.formatted(name, realm).strip();
        }

        return """
                Couldn’t fetch mount progression for %s on %s (EU).
                Try again later, or check the realm and character name.
                """.formatted(name, realm).strip();
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
            ZonedDateTime zagreb = i.atZone(ZAGREB_ZONE);
            return zagreb.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception _) {
            return "n/a";
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception _) {
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
            } catch (Exception _) {
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
            if (ranks.size() >= 3) {
                return ranks.get(2);
            }
            if (ranks.size() >= 2) {
                return ranks.get(1);
            }
        }
        return null;
    }

    private String formatItemPriceOrState(ItemRef item, String emptyLabel) {
        if (item == null) return emptyLabel;
        PriceResult result = isWowToken(item.name())
                ? auctionService.getWowTokenPrice()
                : auctionService.getRegionBuyPrice(item.id());
        return result.available() ? formatCopper(result.avgCopper()) : emptyLabel;
    }

    private static String formatCopper(long copper) {
        long gold = copper / 10_000;
        long silver = copper % 10_000 / 100;
        return gold + "g " + silver + "s";
    }

    private enum TokenPriceExtreme {
        LOWEST(TOKEN_LOWEST_WEEK_COMMAND, TOKEN_LOWEST_MONTH_COMMAND),
        HIGHEST(TOKEN_HIGHEST_WEEK_COMMAND, TOKEN_HIGHEST_MONTH_COMMAND);

        private final String weekCommand;
        private final String monthCommand;

        TokenPriceExtreme(String weekCommand, String monthCommand) {
            this.weekCommand = weekCommand;
            this.monthCommand = monthCommand;
        }

        String commandFor(TokenHistoryPeriod period) {
            return period == TokenHistoryPeriod.WEEK ? weekCommand : monthCommand;
        }

        String usageMessage() {
            return USAGE_PREFIX + weekCommand + " or " + monthCommand;
        }
    }

    private enum TokenHistoryPeriod {
        WEEK(Duration.ofDays(7), "last week"),
        MONTH(TOKEN_MONTH_LOOKBACK, TOKEN_MONTH_LABEL);

        private final Duration lookback;
        private final String label;

        TokenHistoryPeriod(Duration lookback, String label) {
            this.lookback = lookback;
            this.label = label;
        }

        Duration lookback() {
            return lookback;
        }

        String label() {
            return label;
        }
    }

    private enum WowMenuGroup {
        RAIDS,
        SEASON,
        TOKENS,
        MATERIALS,
        TRAVEL
    }

    private enum WowCommandAction {
        RWF("rwf", "World First", "/rwf", WowMenuGroup.RAIDS),
        TITLE("title", "Title Watch", "/title", WowMenuGroup.SEASON),
        TITLE_ZERO_ONE("title_01", "Top 0.1%", "/title01", WowMenuGroup.SEASON),
        RECAP("recap", "Season Recap", "/seasonrecap", WowMenuGroup.SEASON),
        RECAP_DEPLETED("recap_depleted", "Depleted Runs", "/seasonrecapdepleted", WowMenuGroup.SEASON),
        RECAP_ABANDONED("recap_abandoned", "Abandoned Runs", "/seasonrecapabandoned", WowMenuGroup.SEASON),
        TOKEN("token", "Current Price", "/token", WowMenuGroup.TOKENS),
        TOKEN_LOW_WEEK("token_low_week", "Lowest Week", TOKEN_LOWEST_WEEK_COMMAND, WowMenuGroup.TOKENS),
        TOKEN_LOW_MONTH("token_low_month", "Lowest Month", TOKEN_LOWEST_MONTH_COMMAND, WowMenuGroup.TOKENS),
        TOKEN_HIGH_WEEK("token_high_week", "Highest Week", TOKEN_HIGHEST_WEEK_COMMAND, WowMenuGroup.TOKENS),
        TOKEN_HIGH_MONTH("token_high_month", "Highest Month", TOKEN_HIGHEST_MONTH_COMMAND, WowMenuGroup.TOKENS),
        TOKEN_BEST("token_best", "Best Hours", TOKEN_BEST_COMMAND, WowMenuGroup.TOKENS),
        ORES("ores", "Ores", "/ores", WowMenuGroup.MATERIALS),
        HERBS("herbs", "Herbs", "/herbs", WowMenuGroup.MATERIALS),
        ROAD_ZADAR_ZAGREB("road_zadar_zagreb", "Zadar → Zagreb", "/road zadar zagreb", WowMenuGroup.TRAVEL),
        ROAD_ZAGREB_ZADAR("road_zagreb_zadar", "Zagreb → Zadar", "/road zagreb zadar", WowMenuGroup.TRAVEL),
        ROAD_BEST_ZADAR_ZAGREB(
                "road_best_zadar_zagreb",
                "Best Zadar → Zagreb",
                "/roadbest zadar zagreb",
                WowMenuGroup.TRAVEL
        ),
        ROAD_BEST_ZAGREB_ZADAR(
                "road_best_zagreb_zadar",
                "Best Zagreb → Zadar",
                "/roadbest zagreb zadar",
                WowMenuGroup.TRAVEL
        );

        private final String key;
        private final String label;
        private final String commandText;
        private final WowMenuGroup group;

        WowCommandAction(String key, String label, String commandText, WowMenuGroup group) {
            this.key = key;
            this.label = label;
            this.commandText = commandText;
            this.group = group;
        }

        String key() {
            return key;
        }

        String label() {
            return label;
        }

        String commandText() {
            return commandText;
        }

        WowMenuGroup group() {
            return group;
        }

        static WowCommandAction fromKey(String key) {
            for (WowCommandAction action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
            return null;
        }
    }

    private enum CharacterReportAction {
        RAIDER_IO("rio", "Raider.IO Score"),
        ITEM_LEVEL(ITEM_LEVEL_CALLBACK, ITEM_LEVEL_LABEL),
        MOUNTS("mount", "Mount Progress");

        private final String key;
        private final String label;

        CharacterReportAction(String key, String label) {
            this.key = key;
            this.label = label;
        }

        String key() {
            return key;
        }

        String label() {
            return label;
        }

        static boolean isSupported(String key) {
            return fromKey(key) != null;
        }

        static CharacterReportAction fromKey(String key) {
            for (CharacterReportAction action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
            return null;
        }
    }

    private record CharacterReportRow(
            TrackedPlayer profile,
            RaiderIoClient.RaiderIoScore score,
            BlizzardMountService.MountProgress mountProgress
    ) {
        private boolean unavailable() {
            return score == null && mountProgress == null;
        }

        private BigDecimal metric(CharacterReportAction action) {
            return switch (action) {
                case RAIDER_IO -> score == null ? null : score.all();
                case ITEM_LEVEL -> score == null ? null : score.itemLevel();
                case MOUNTS -> mountProgress == null ? null : BigDecimal.valueOf(mountProgress.usable());
            };
        }
    }

    private enum MPlusMenuAction {
        PROGRESS(RAID_PROGRESS_CALLBACK, "Progress", true),
        DUNGEONS("dungeons", "Dungeons", true),
        VAULT(RAID_VAULT_CALLBACK, "Vault", true),
        PERFORMANCE("performance", "Performance", true),
        HIGHLIGHTS("highlights", "Highlights", true),
        TEAM("team", "Team", true),
        PAIR(MPLUS_PAIR_CALLBACK, "Pair", false),
        CONSISTENCY("consistency", "Consistency", true),
        AWARDS("awards", "Awards", false),
        COVERAGE("coverage", "Coverage", true),
        COMBAT(RAID_COMBAT_CALLBACK, "Combat", true),
        STATUS("status", "Status", false);

        private final String key;
        private final String label;
        private final boolean requiresProfile;

        MPlusMenuAction(String key, String label, boolean requiresProfile) {
            this.key = key;
            this.label = label;
            this.requiresProfile = requiresProfile;
        }

        String key() {
            return key;
        }

        String label() {
            return label;
        }

        boolean requiresProfile() {
            return requiresProfile;
        }

        boolean supportsAllProfiles() {
            return this == PROGRESS || this == TEAM || this == COMBAT;
        }

        static MPlusMenuAction fromKey(String key) {
            for (MPlusMenuAction action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface CommandHandler {
        boolean handle(CommandContext context);
    }

    private record MPlusRequest(String section, String arguments) {

        private static MPlusRequest from(String text) {
            String[] parts = text.trim().split("\\s+", 3);
            if (parts.length < 2) {
                return new MPlusRequest("help", "");
            }
            return new MPlusRequest(
                    parts[1].toLowerCase(Locale.ROOT),
                    parts.length == 3 ? parts[2].trim() : ""
            );
        }
    }

    private record CommandContext(long chatId, Long senderUserId, String text, String command) {

        private static CommandContext from(Update update) {
            if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
                return null;
            }

            Long senderUserId = update.getMessage().getFrom() == null
                    ? null
                    : update.getMessage().getFrom().getId();
            return fromText(update.getMessage().getChatId(), senderUserId, update.getMessage().getText());
        }

        private static CommandContext forCallback(long chatId, Long senderUserId, String text) {
            return fromText(chatId, senderUserId, text);
        }

        private static CommandContext fromText(long chatId, Long senderUserId, String inputText) {
            String text = inputText.trim();
            String[] commandAndArguments = text.split("\\s+", 2);
            String command = commandAndArguments[0];
            int mentionSeparator = command.indexOf('@');
            if (mentionSeparator >= 0) {
                command = command.substring(0, mentionSeparator);
            }
            String arguments = commandAndArguments.length == 2 ? commandAndArguments[1] : "";
            NormalizedCommand normalized = normalizeSnakeCaseCommand(command);
            if (normalized != null) {
                command = normalized.routedCommand();
                text = normalized.rewrittenPrefix() + (arguments.isBlank() ? "" : " " + arguments);
            }

            return new CommandContext(chatId, senderUserId, text, command);
        }

        private static NormalizedCommand normalizeSnakeCaseCommand(String command) {
            if (!command.contains("_") || command.startsWith("/token_")) {
                return null;
            }
            if (command.startsWith("/mplus_")) {
                String section = command.substring("/mplus_".length());
                return new NormalizedCommand("/mplus", "/mplus " + section);
            }
            return switch (command) {
                case "/profile_help" -> NormalizedCommand.direct("/profile-help");
                case "/help_admin" -> NormalizedCommand.direct("/help-admin");
                case "/mount_achievement" -> NormalizedCommand.direct("/mount-achiv");
                case "/road_zadar_zagreb" -> new NormalizedCommand(ROAD_COMMAND, "/road zadar zagreb");
                case "/road_zagreb_zadar" -> new NormalizedCommand(ROAD_COMMAND, "/road zagreb zadar");
                case "/road_best_zadar_zagreb" -> new NormalizedCommand(
                        ROAD_BEST_COMMAND,
                        "/roadbest zadar zagreb"
                );
                case "/road_best_zagreb_zadar" -> new NormalizedCommand(
                        ROAD_BEST_COMMAND,
                        "/roadbest zagreb zadar"
                );
                default -> NormalizedCommand.direct(command.replace("_", ""));
            };
        }
    }

    private record NormalizedCommand(String routedCommand, String rewrittenPrefix) {

        private static NormalizedCommand direct(String command) {
            return new NormalizedCommand(command, command);
        }
    }

    private record PendingAltKey(long chatId, long telegramUserId) {
    }

    private record PendingAltAddition(long profileId, String profileName, Instant expiresAt) {
    }

}
