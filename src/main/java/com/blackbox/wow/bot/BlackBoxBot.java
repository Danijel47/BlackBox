package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.helper.AffixFormatter;
import com.blackbox.wow.helper.RaidPicker;
import com.blackbox.wow.helper.RaidProgressFormatter;
import com.blackbox.wow.helper.VaultSlotCalculator;
import com.blackbox.wow.helper.VaultSlotCalculator.VaultSlots;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.RaiderIoAbandonedRunService;
import com.blackbox.wow.service.RaceToWorldFirstService;
import com.blackbox.wow.service.MPlusDataCollectionService;
import com.blackbox.wow.service.MPlusProgressService;
import com.blackbox.wow.service.MPlusDungeonVaultService;
import com.blackbox.wow.service.MPlusPerformanceService;
import com.blackbox.wow.service.MPlusAdvancedService;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.MPlusTeamService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.TelegramAccessPolicy;
import com.blackbox.wow.service.TelegramBotUserService;
import com.blackbox.wow.service.TelegramDailyPromptService;
import com.blackbox.wow.service.VaultReminderService;
import com.blackbox.wow.service.WowTokenPriceHistoryService;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenHourAverage;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenPricePoint;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenTradingHours;
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
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Component
public class BlackBoxBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final int INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS = 600;
    private static final Duration TITLE_WATCH_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TITLE_PREDICTION_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration SEASON_RECAP_CACHE_TTL = Duration.ofHours(24);
    private static final Duration FAILED_SEASON_RECAP_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration TOKEN_MONTH_LOOKBACK = Duration.ofDays(30);
    private static final String TOKEN_MONTH_LABEL = "last 30 days";
    private static final String TELEGRAM_USER_UNAVAILABLE =
            "Telegram user information is unavailable for this message.";
    private static final String PROFILE_PREFIX = "Profile ";
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
    private static final int INLINE_BUTTONS_PER_ROW = 2;
    private static final int MAX_PROFILE_BUTTONS = 90;
    private static final ZoneId ZAGREB_ZONE = ZoneId.of("Europe/Zagreb");
    private static final DateTimeFormatter TOKEN_HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "d MMM uuuu, HH:mm z",
            Locale.ENGLISH
    );
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
    private final WowTokenPriceHistoryService tokenPriceHistoryService;
    private final BlizzardItemService itemService;
    private final BlizzardMountService mountService;
    private final TimeToGoCommandService timeToGoCommands;
    private final RaiderIoAbandonedRunService abandonedRunService;
    private final TrackedPlayerService trackedPlayerService;
    private final VaultReminderService vaultReminderService;
    private final RaceToWorldFirstService raceToWorldFirstService;
    private final MPlusDataCollectionService mplusDataCollectionService;
    private final MPlusProgressService mplusProgressService;
    private final MPlusDungeonVaultService mplusDungeonVaultService;
    private final MPlusPerformanceService mplusPerformanceService;
    private final MPlusTeamService mplusTeamService;
    private final MPlusAdvancedService mplusAdvancedService;
    private final MPlusRunCorrelationService mplusRunCorrelationService;
    private final WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    private final TelegramAccessPolicy telegramAccessPolicy;
    private final TelegramBotUserService telegramBotUserService;
    private final TelegramDailyPromptService telegramDailyPromptService;
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
            WowTokenPriceHistoryService tokenPriceHistoryService,
            BlizzardItemService itemService,
            BlizzardMountService mountService,
            TimeToGoCommandService timeToGoCommands,
            RaiderIoAbandonedRunService abandonedRunService,
            TrackedPlayerService trackedPlayerService,
            VaultReminderService vaultReminderService,
            RaceToWorldFirstService raceToWorldFirstService,
            MPlusDataCollectionService mplusDataCollectionService,
            MPlusProgressService mplusProgressService,
            MPlusDungeonVaultService mplusDungeonVaultService,
            MPlusPerformanceService mplusPerformanceService,
            MPlusTeamService mplusTeamService,
            MPlusAdvancedService mplusAdvancedService,
            MPlusRunCorrelationService mplusRunCorrelationService,
            WarcraftLogsStatisticsService warcraftLogsStatisticsService,
            TelegramAccessPolicy telegramAccessPolicy,
            TelegramBotUserService telegramBotUserService,
            TelegramDailyPromptService telegramDailyPromptService
    ) {
        this.token = token;
        this.adminUserId = adminUserId;
        this.client = client;
        this.raiderIoClient = raiderIoClient;
        this.defaultGuildProps = defaultGuildProps;
        this.watchlistProps = watchlistProps;
        this.auctionService = auctionService;
        this.tokenPriceHistoryService = tokenPriceHistoryService;
        this.itemService = itemService;
        this.mountService = mountService;
        this.timeToGoCommands = timeToGoCommands;
        this.abandonedRunService = abandonedRunService;
        this.trackedPlayerService = trackedPlayerService;
        this.vaultReminderService = vaultReminderService;
        this.raceToWorldFirstService = raceToWorldFirstService;
        this.mplusDataCollectionService = mplusDataCollectionService;
        this.mplusProgressService = mplusProgressService;
        this.mplusDungeonVaultService = mplusDungeonVaultService;
        this.mplusPerformanceService = mplusPerformanceService;
        this.mplusTeamService = mplusTeamService;
        this.mplusAdvancedService = mplusAdvancedService;
        this.mplusRunCorrelationService = mplusRunCorrelationService;
        this.warcraftLogsStatisticsService = warcraftLogsStatisticsService;
        this.telegramAccessPolicy = telegramAccessPolicy;
        this.telegramBotUserService = telegramBotUserService;
        this.telegramDailyPromptService = telegramDailyPromptService;
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
            default -> null;
        };
        if (adminAction == null) {
            return false;
        }
        runAdminCommand(context, adminAction);
        return true;
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
            send(context.chatId(), "Telegram user " + telegramUserId + " is now allowed.");
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not add user: " + e.getMessage());
        }
    }

    private void changeTelegramUserStatus(CommandContext context) {
        String[] parts = context.text().split("\\s+");
        boolean active = context.command().equals("/userenable");
        if (parts.length != 2) {
            send(context.chatId(), "Usage: " + (active ? "/user_enable" : "/user_disable")
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
            send(context.chatId(), "Telegram user " + telegramUserId
                    + (active ? " enabled." : " disabled."));
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not update user: " + e.getMessage());
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
            case "progress" -> mplusProgressService.progressMessage(arguments, senderUserId);
            case "dungeons" -> mplusDungeonVaultService.dungeonCoverageMessage(
                    arguments, senderUserId
            );
            case "vault" -> mplusDungeonVaultService.currentVaultMessage(
                    arguments, senderUserId
            );
            case "performance" -> mplusPerformanceService.performanceMessage(
                    arguments, senderUserId
            );
            case "highlights" -> mplusPerformanceService.highlightsMessage(
                    arguments, senderUserId
            );
            case "team" -> mplusTeamService.teamMessage(arguments, senderUserId);
            case "pair" -> mplusTeamService.pairMessage(arguments);
            case "consistency" -> mplusAdvancedService.consistencyMessage(
                    arguments, senderUserId
            );
            case "awards" -> mplusAdvancedService.awardsMessage();
            case "coverage" -> mplusRunCorrelationService.coverageMessage(
                    arguments, senderUserId
            );
            case "combat" -> warcraftLogsStatisticsService.combatMessage(arguments);
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
            sendProfileMenu(chatId, "Choose the first profile:", MPLUS_PAIR_FIRST_CALLBACK, null);
            return;
        }
        if (action.requiresProfile()) {
            sendProfileMenu(
                    chatId,
                    "Choose a profile for " + action.label() + ":",
                    MPLUS_PROFILE_CALLBACK + ":" + action.key(),
                    null
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
                firstProfile.profileId()
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

    private void sendProfileMenu(long chatId, String prompt, String callbackAction, Long excludedProfileId) {
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        List<InlineKeyboardButton> buttons = new ArrayList<>();
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
        buttons.add(inlineButton("Back", MPLUS_CALLBACK_PREFIX + MPLUS_MENU_CALLBACK));
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
                wowMenuButton("Profiles", WOW_PROFILES_CALLBACK),
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
        send(chatId, "That WoW menu selection is no longer valid. Use /wow to start again.");
    }

    private void openWowSubmenu(long chatId, String menuKey) {
        switch (menuKey) {
            case "mplus" -> sendMPlusMenu(chatId);
            case WOW_PROFILES_CALLBACK -> sendProfilesMenu(chatId);
            case WOW_CHARACTER_CALLBACK -> sendCharacterMenu(chatId);
            case "raids" -> sendCommandMenu(chatId, "Choose a raid report:", WowMenuGroup.RAIDS);
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
        buttons.add(inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
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

    private void sendProfilesMenu(long chatId) {
        send(chatId, "Choose a profile action:", inlineKeyboard(List.of(
                inlineButton("My Profile", WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":view"),
                inlineButton("Select Main", WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":main"),
                inlineButton("Group Mains", WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":mains"),
                inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        )));
    }

    private void routeProfilesCallback(long chatId, long senderUserId, String[] parts) {
        if (parts.length == 3 && parts[2].equals("view")) {
            dispatchCommand(CommandContext.forCallback(chatId, senderUserId, "/profile"));
        } else if (parts.length == 3 && parts[2].equals("mains")) {
            dispatchCommand(CommandContext.forCallback(chatId, senderUserId, "/mains"));
        } else if (parts.length == 3 && parts[2].equals("main")) {
            sendOwnedCharacterMenu(chatId, senderUserId);
        } else if (parts.length == 4 && parts[2].equals("select")) {
            selectOwnedCharacter(chatId, senderUserId, parts[3]);
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
                    WOW_CALLBACK_PREFIX + WOW_PROFILES_CALLBACK + ":select:" + index
            ));
        }
        buttons.add(inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":" + WOW_PROFILES_CALLBACK));
        send(chatId, "Choose your main character:", inlineKeyboard(buttons));
    }

    private void selectOwnedCharacter(long chatId, long senderUserId, String characterIndexValue) {
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
                        inlineButton("Profiles", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":"
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
                inlineButton("Weekly Vault", WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":vault"),
                inlineButton("Mount Progress", WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":mount"),
                inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
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
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (TrackedPlayer profile : trackedPlayerService.activePlayers()) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            buttons.add(inlineButton(
                    profile.profileName(),
                    WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":" + action.key() + ":" + profile.profileId()
            ));
        }
        if (buttons.isEmpty()) {
            send(chatId, "No active profiles are available.");
            return;
        }
        buttons.add(inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":" + WOW_CHARACTER_CALLBACK));
        send(chatId, "Choose a profile for " + action.label() + ":", inlineKeyboard(buttons));
    }

    private void runCharacterReport(long chatId, String actionKey, String profileIdValue) {
        CharacterReportAction action = CharacterReportAction.fromKey(actionKey);
        TrackedPlayer profile = findActiveProfile(profileIdValue);
        if (action == null || profile == null) {
            send(chatId, "That character or report is no longer available. Use /wow to refresh the menu.");
            return;
        }
        switch (action) {
            case RAIDER_IO -> send(chatId, formatRaiderIoScore(raiderIoClient.getCurrentMPlusScore(
                    profile.region(), profile.realm(), profile.name()
            )));
            case VAULT -> send(chatId, formatWeeklyVault(raiderIoClient.getWeeklyVaultProgress(
                    profile.region(), profile.realm(), profile.name()
            )));
            case MOUNTS -> send(chatId, "Insurmountable Collection: " + formatMountAchievementProgress(
                    mountService.getMountProgress(profile.realm(), profile.name()).usable()
            ));
        }
    }

    private void sendWowAdminMenu(long chatId, Long senderUserId) {
        if (!isAdmin(senderUserId)) {
            send(chatId, adminOnlyMessage());
            return;
        }
        send(chatId, "Choose an admin action:", inlineKeyboard(List.of(
                adminButton("Users", "users"),
                adminButton("User Access", "user_access"),
                adminButton("Profiles", WOW_PROFILES_CALLBACK),
                adminButton("Profile Access", "profile_access"),
                adminCommandButton("M+ Status", "mplus_status"),
                adminCommandButton("Vault Reminder", "vault_reminder"),
                adminCommandButton("Travel Import", "travel_import"),
                adminCommandButton("Import Status", "import_status"),
                adminCommandButton("Group ID", "group_id"),
                inlineButton("Public WoW Menu", WOW_ADMIN_CALLBACK_PREFIX + "public")
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
        if (parts.length == 2 && parts[1].equals(WOW_MENU_CALLBACK)) {
            sendWowAdminMenu(chatId, senderUserId);
        } else if (parts.length == 2 && parts[1].equals("public")) {
            sendWowMenu(chatId);
        } else if (parts.length == 2 && parts[1].equals("users")) {
            send(chatId, formatTelegramUsers(), adminBackKeyboard());
        } else if (parts.length == 2 && parts[1].equals("user_access")) {
            sendAdminUserAccessMenu(chatId);
        } else if (parts.length == 2 && parts[1].equals(WOW_PROFILES_CALLBACK)) {
            send(chatId, formatPlayerProfiles(), adminBackKeyboard());
        } else if (parts.length == 2 && parts[1].equals("profile_access")) {
            sendAdminProfileAccessMenu(chatId);
        } else if (parts.length == 3 && parts[1].equals(WOW_COMMAND_CALLBACK)) {
            runWowAdminAction(chatId, senderUserId, parts[2]);
        } else if (parts.length == 4 && parts[1].equals("user")) {
            changeUserAccessFromButton(chatId, parts[2], parts[3]);
        } else if (parts.length == 4 && parts[1].equals("profile")) {
            changeProfileAccessFromButton(chatId, parts[2], parts[3]);
        } else {
            send(chatId, "That admin selection is no longer valid. Use /wow_admin to start again.");
        }
    }

    private InlineKeyboardMarkup adminBackKeyboard() {
        return inlineKeyboard(List.of(inlineButton("Back", WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK)));
    }

    private void runWowAdminAction(long chatId, long senderUserId, String actionKey) {
        switch (actionKey) {
            case "mplus_status" -> send(chatId, adminMPlusStatus(senderUserId), adminBackKeyboard());
            case "vault_reminder" -> send(chatId, vaultReminderService.checkNowMessage(), adminBackKeyboard());
            case "travel_import" -> send(chatId, timeToGoCommands.submitHistoricalImport(), adminBackKeyboard());
            case "import_status" -> send(chatId, timeToGoCommands.refreshHistoricalImport(), adminBackKeyboard());
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
                    (enable ? "Enable " : "Disable ") + displayName,
                    WOW_ADMIN_CALLBACK_PREFIX + "user:" + user.getTelegramUserId() + ":" + enable
            ));
        }
        if (buttons.isEmpty()) {
            send(chatId, "No Telegram users are registered.", adminBackKeyboard());
            return;
        }
        buttons.add(inlineButton("Back", WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
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
        send(chatId, "Telegram user " + userId + (active ? " enabled." : " disabled."), adminBackKeyboard());
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
                    (enable ? "Enable " : "Disable ") + profile.name(),
                    WOW_ADMIN_CALLBACK_PREFIX + "profile:" + profile.id() + ":" + enable
            ));
        }
        if (buttons.isEmpty()) {
            send(chatId, "No player profiles are configured.", adminBackKeyboard());
            return;
        }
        buttons.add(inlineButton("Back", WOW_ADMIN_CALLBACK_PREFIX + WOW_MENU_CALLBACK));
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
            send(chatId, "That profile no longer exists. Use /wow_admin to refresh the menu.");
            return;
        }
        trackedPlayerService.setProfileActive(profile.id(), active);
        send(chatId, PROFILE_PREFIX + profile.name() + (active ? " enabled." : " disabled."), adminBackKeyboard());
    }

    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
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
            send(context.chatId(), "Usage: " + (active ? "/profile_enable" : "/profile_disable")
                    + " <profile>");
            return;
        }
        try {
            trackedPlayerService.setProfileActive(parts[1], active);
            send(context.chatId(), PROFILE_PREFIX + parts[1] + (active ? " enabled." : " disabled."));
        } catch (IllegalArgumentException e) {
            send(context.chatId(), "Could not update profile: " + e.getMessage());
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
            default -> false;
        };
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
        try {
            PriceResult result = auctionService.getWowTokenPrice();
            send(chatId, formatPriceMessage(WOW_TOKEN_EU_SCOPE, "WoW Token", result));
        } catch (Exception e) {
            send(chatId, "Blizzard token lookup failed: " + e.getMessage());
        }
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
            send(context.chatId(), "Usage: " + extreme.commandFor(period));
            return;
        }

        try {
            Instant capturedAt = Instant.now().minus(period.lookback());
            var price = extreme == TokenPriceExtreme.LOWEST
                    ? tokenPriceHistoryService.lowestPriceSince(capturedAt)
                    : tokenPriceHistoryService.highestPriceSince(capturedAt);
            if (price.isEmpty()) {
                send(context.chatId(), "No saved WoW Token prices for the " + period.label() + " yet.");
                return;
            }
            send(context.chatId(), formatTokenPriceExtreme(price.get(), period.label(), extreme));
        } catch (RuntimeException _) {
            send(context.chatId(), "Could not read the WoW Token price history.");
        }
    }

    private static String formatTokenPriceExtreme(
            TokenPricePoint price,
            String periodLabel,
            TokenPriceExtreme extreme
    ) {
        String priceTime = price.priceAt()
                .atZone(ZAGREB_ZONE)
                .format(TOKEN_HISTORY_TIME_FORMATTER);
        return extreme.displayName() + " WoW Token price (EU) in the " + periodLabel + ": "
                + formatCopper(price.priceCopper())
                + "\nDate: " + priceTime;
    }

    private void handleBestTokenTradingHours(CommandContext context) {
        if (!commandArguments(context).isBlank()) {
            send(context.chatId(), "Usage: " + TOKEN_BEST_COMMAND);
            return;
        }
        try {
            var tradingHours = tokenPriceHistoryService.bestTradingHoursSince(
                    Instant.now().minus(TOKEN_MONTH_LOOKBACK),
                    ZAGREB_ZONE
            );
            if (tradingHours.isEmpty()) {
                send(context.chatId(), "Not enough WoW Token history yet. Each hour needs at least "
                        + WowTokenPriceHistoryService.MINIMUM_SAMPLES_PER_HOUR + " samples.");
                return;
            }
            send(context.chatId(), formatBestTokenTradingHours(tradingHours.get()));
        } catch (RuntimeException _) {
            send(context.chatId(), "Could not analyze the WoW Token price history.");
        }
    }

    private static String formatBestTokenTradingHours(TokenTradingHours tradingHours) {
        return "Best recurring WoW Token times (EU, " + TOKEN_MONTH_LABEL + "; Europe/Zagreb):\n"
                + "Buy with gold: " + formatTokenHour(tradingHours.buy()) + "\n"
                + "Sell for gold: " + formatTokenHour(tradingHours.sell())
                + "\nBased on hourly averages; historical patterns do not guarantee future prices.";
    }

    private static String formatTokenHour(TokenHourAverage hour) {
        return "%02d:00–%02d:59 — avg %s (%d daily samples)".formatted(
                hour.hour(),
                hour.hour(),
                formatCopper(hour.averageCopper()),
                hour.sampleCount()
        );
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
        return switch (context.command()) {
            case "/road", "/travel", "/timetogo" -> handled(() -> send(
                    context.chatId(),
                    timeToGoCommands.formatCurrent(context.text())
            ));
            case "/roadbest", "/travelbest", "/timetogobest" -> handled(() -> send(
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
            send(context.chatId(), formatWeeklyVaultWatch());
            return;
        }
        if (parts.length != 3 && parts.length != 4) {
            send(context.chatId(), "Usage: /vault\nOptional Mythic+ lookup: /vault <realm> <name>");
            return;
        }

        String region = parts.length == 4 ? parts[1].toLowerCase(Locale.ROOT) : "eu";
        String realm = parts.length == 4 ? parts[2] : parts[1];
        String name = parts.length == 4 ? parts[3] : parts[2];
        try {
            send(context.chatId(), formatWeeklyVault(raiderIoClient.getWeeklyVaultProgress(region, realm, name)));
        } catch (Exception _) {
            send(context.chatId(), """
                    Couldn’t fetch weekly Mythic+ vault data for %s on %s (%s).
                    Use: /vault <realm> <name>
                    Example: /vault stormscale bucothered
                    """.formatted(name, realm, region).strip());
        }
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
            } catch (Exception _) {
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
            } catch (Exception _) {
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
        VaultSlots slots = VaultSlotCalculator.calculate(runs.stream()
                .map(RaiderIoClient.MPlusRun::level)
                .toList());
        Integer level = switch (requiredRuns) {
            case 1 -> slots.slotOne();
            case 4 -> slots.slotFour();
            case 8 -> slots.slotEight();
            default -> throw new IllegalArgumentException("Unsupported vault slot: " + requiredRuns);
        };
        if (level == null) {
            return "locked (" + (requiredRuns - slots.runCount()) + " more)";
        }
        return "+" + level;
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
        if (cutoffScore == null) {
            return "M+ 0.1% title watch is unavailable because Raider.IO returned no cutoff score.";
        }
        StringBuilder sb = new StringBuilder("M+ 0.1% title watch\n");
        sb.append("Cutoff: ").append(formatScore(cutoffScore))
                .append("\nCutoff updated: ").append(formatCutoffUpdatedAt(cutoff.updatedAt()))
                .append("\n");
        appendTitlePrediction(sb, "p999", player.region());

        try {
            var score = raiderIoClient.getCurrentMPlusScore(player.region(), player.realm(), player.name());
            BigDecimal all = score.all();
            TitleScoreDelta delta = calculateTitleScoreDelta(all, cutoffScore);
            sb.append("• ").append(score.name()).append(": ").append(formatTitleScoreLine(
                    all,
                    delta.remaining(),
                    delta.above()
            ));
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
        if (isCurrentTitleWatchCache(players, now)) {
            return titleWatchCache.message();
        }

        var cutoff = raiderIoClient.getCurrentMPlusTitleCutoff(players.getFirst().region());
        BigDecimal cutoffScore = cutoff.score();
        if (cutoffScore == null) {
            return "M+ 1% title watch is unavailable because Raider.IO returned no cutoff score.";
        }
        List<TitleWatchResult> results = loadTitleWatchResults(players, cutoffScore);
        results.sort(Comparator.comparing(
                TitleWatchResult::score,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        String message = formatTitleWatchMessage(players, cutoff, cutoffScore, results);
        titleWatchCache = new TitleWatchCache(message, players, now.plus(TITLE_WATCH_CACHE_TTL));
        return message;
    }

    private boolean isCurrentTitleWatchCache(List<TrackedPlayer> players, Instant now) {
        return titleWatchCache != null
                && titleWatchCache.expiresAt().isAfter(now)
                && titleWatchCache.players().equals(players);
    }

    private List<TitleWatchResult> loadTitleWatchResults(
            List<TrackedPlayer> players,
            BigDecimal cutoffScore
    ) {
        List<TitleWatchResult> results = new ArrayList<>();
        for (TrackedPlayer player : players) {
            results.add(loadTitleWatchResult(player, cutoffScore));
        }
        return results;
    }

    private TitleWatchResult loadTitleWatchResult(TrackedPlayer player, BigDecimal cutoffScore) {
        if (cutoffScore == null) {
            return new TitleWatchResult(
                    player.name(),
                    player.realm(),
                    player.region(),
                    null,
                    null,
                    null,
                    "cutoff score unavailable"
            );
        }
        try {
            var score = raiderIoClient.getCurrentMPlusScore(player.region(), player.realm(), player.name());
            BigDecimal all = score.all();
            TitleScoreDelta delta = calculateTitleScoreDelta(all, cutoffScore);
            return new TitleWatchResult(
                    score.name(),
                    score.realm(),
                    score.region(),
                    all,
                    delta.remaining(),
                    delta.above(),
                    null
            );
        } catch (Exception e) {
            return new TitleWatchResult(
                    player.name(),
                    player.realm(),
                    player.region(),
                    null,
                    null,
                    null,
                    e.getMessage()
            );
        }
    }

    private String formatTitleWatchMessage(
            List<TrackedPlayer> players,
            RaiderIoClient.MPlusTitleCutoff cutoff,
            BigDecimal cutoffScore,
            List<TitleWatchResult> results
    ) {
        StringBuilder message = new StringBuilder("M+ 1% title watch\n");
        message.append("Cutoff: ").append(formatScore(cutoffScore))
                .append("\nCutoff updated: ").append(formatCutoffUpdatedAt(cutoff.updatedAt()))
                .append("\n");
        appendTitlePrediction(message, "p990", players.getFirst().region());
        results.forEach(result -> appendTitleWatchResult(message, result));
        return message.toString().trim();
    }

    private static void appendTitleWatchResult(StringBuilder message, TitleWatchResult result) {
        message.append("• ").append(result.name()).append(": ");
        if (result.error() != null) {
            message.append("error: ").append(result.error()).append("\n");
            return;
        }
        message.append(formatTitleScoreLine(result.score(), result.remaining(), result.above())).append("\n");
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
        } catch (Exception _) {
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

    private static TitleScoreDelta calculateTitleScoreDelta(BigDecimal score, BigDecimal cutoffScore) {
        if (score == null || cutoffScore == null) {
            return new TitleScoreDelta(null, null);
        }
        return new TitleScoreDelta(
                cutoffScore.subtract(score).max(BigDecimal.ZERO),
                score.subtract(cutoffScore).max(BigDecimal.ZERO)
        );
    }

    private static String formatRaiderIoScore(RaiderIoClient.RaiderIoScore score) {
        String profile = score.profileUrl() == null || score.profileUrl().isBlank()
                ? ""
                : "%nProfile: %s".formatted(score.profileUrl());
        return """
                Raider.IO (current season)
                %s - %s (%s)
                Score: %s
                DPS: %s | Healer: %s | Tank: %s%s
                """.formatted(
                score.name(),
                score.realm(),
                score.region(),
                valueOrUnavailable(score.all()),
                valueOrUnavailable(score.dps()),
                valueOrUnavailable(score.healer()),
                valueOrUnavailable(score.tank()),
                profile
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

    private static String formatCutoffUpdatedAt(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) return "n/a";
        try {
            ZonedDateTime utc = ZonedDateTime.parse(
                    updatedAt,
                    DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z '('zzzz')'", Locale.ENGLISH)
            );
            return utc.withZoneSameInstant(ZAGREB_ZONE)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        } catch (Exception _) {
            return updatedAt;
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

    private static String formatScore(BigDecimal score) {
        if (score == null) return "n/a";
        return score.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatPredictionFor(Instant predictionFor) {
        if (predictionFor == null) return "n/a";
        return predictionFor.atZone(ZAGREB_ZONE)
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
                : auctionService.getRegionAverage(item.id());
        return result.available() ? formatCopper(result.avgCopper()) : emptyLabel;
    }

    private static String formatCopper(long copper) {
        long gold = copper / 10_000;
        long silver = copper % 10_000 / 100;
        return gold + "g " + silver + "s";
    }

    private enum TokenPriceExtreme {
        LOWEST("Lowest", TOKEN_LOWEST_WEEK_COMMAND, TOKEN_LOWEST_MONTH_COMMAND),
        HIGHEST("Highest", TOKEN_HIGHEST_WEEK_COMMAND, TOKEN_HIGHEST_MONTH_COMMAND);

        private final String displayName;
        private final String weekCommand;
        private final String monthCommand;

        TokenPriceExtreme(String displayName, String weekCommand, String monthCommand) {
            this.displayName = displayName;
            this.weekCommand = weekCommand;
            this.monthCommand = monthCommand;
        }

        String commandFor(TokenHistoryPeriod period) {
            return period == TokenHistoryPeriod.WEEK ? weekCommand : monthCommand;
        }

        String usageMessage() {
            return "Usage: " + weekCommand + " or " + monthCommand;
        }

        String displayName() {
            return displayName;
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

    private record TitleScoreDelta(BigDecimal remaining, BigDecimal above) {
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

    private enum WowMenuGroup {
        RAIDS,
        SEASON,
        TOKENS,
        MATERIALS,
        TRAVEL
    }

    private enum WowCommandAction {
        RWF("rwf", "World First", "/rwf", WowMenuGroup.RAIDS),
        VAULT("vault", "Vault Watch", "/vault", WowMenuGroup.RAIDS),
        AFFIXES("affixes", "Affixes", "/affixes", WowMenuGroup.RAIDS),
        GUILD("guild", "Guild Progress", "/guild", WowMenuGroup.RAIDS),
        GUILD_LIST("guild_list", "Raid List", "/guildlist", WowMenuGroup.RAIDS),
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
        VAULT("vault", "Weekly Vault"),
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

    private enum MPlusMenuAction {
        PROGRESS("progress", "Progress", true),
        DUNGEONS("dungeons", "Dungeons", true),
        VAULT("vault", "Vault", true),
        PERFORMANCE("performance", "Performance", true),
        HIGHLIGHTS("highlights", "Highlights", true),
        TEAM("team", "Team", true),
        PAIR("pair", "Pair", false),
        CONSISTENCY("consistency", "Consistency", true),
        AWARDS("awards", "Awards", false),
        COVERAGE("coverage", "Coverage", true),
        COMBAT("combat", "Combat", true),
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
                case "/road_zadar_zagreb" -> new NormalizedCommand("/road", "/road zadar zagreb");
                case "/road_zagreb_zadar" -> new NormalizedCommand("/road", "/road zagreb zadar");
                case "/road_best_zadar_zagreb" -> new NormalizedCommand(
                        "/roadbest",
                        "/roadbest zadar zagreb"
                );
                case "/road_best_zagreb_zadar" -> new NormalizedCommand(
                        "/roadbest",
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

}
