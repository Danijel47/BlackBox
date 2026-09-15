package com.blackbox.wow.bot;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.helper.RaidPicker;
import com.blackbox.wow.helper.RaidProgressFormatter;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.service.RaceToWorldFirstService;
import com.blackbox.wow.service.RaidReportService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class RaidCommandHandler {

    private static final String WOW_CALLBACK_PREFIX = "wow:";
    private static final String WOW_MENU_CALLBACK = "menu";
    private static final String WOW_COMMAND_CALLBACK = "command";
    private static final String WOW_RAID_CALLBACK = "raid";
    private static final String RAID_PROGRESS_CALLBACK = "progress";
    private static final String RAID_VAULT_CALLBACK = "vault";
    private static final String RAID_COMBAT_CALLBACK = "combat";
    private static final String ALL_PROFILES_CALLBACK = "all";
    private static final String NO_ACTIVE_PROFILES_MESSAGE = "No active profiles are available.";
    private static final int INLINE_BUTTONS_PER_ROW = 2;
    private static final int MAX_PROFILE_BUTTONS = 90;
    private static final ZoneId ZAGREB_ZONE = ZoneId.of("Europe/Zagreb");

    private final RaiderIoClient raiderIoClient;
    private final RaiderIoDefaultGuildProperties defaultGuildProperties;
    private final TrackedPlayerService trackedPlayerService;
    private final RaceToWorldFirstService raceToWorldFirstService;
    private final RaidReportService raidReportService;
    private final WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    private final MessageSender messageSender;

    RaidCommandHandler(
            RaiderIoClient raiderIoClient,
            RaiderIoDefaultGuildProperties defaultGuildProperties,
            TrackedPlayerService trackedPlayerService,
            RaceToWorldFirstService raceToWorldFirstService,
            RaidReportService raidReportService,
            WarcraftLogsStatisticsService warcraftLogsStatisticsService,
            MessageSender messageSender
    ) {
        this.raiderIoClient = raiderIoClient;
        this.defaultGuildProperties = defaultGuildProperties;
        this.trackedPlayerService = trackedPlayerService;
        this.raceToWorldFirstService = raceToWorldFirstService;
        this.raidReportService = raidReportService;
        this.warcraftLogsStatisticsService = warcraftLogsStatisticsService;
        this.messageSender = messageSender;
    }

    boolean handle(long chatId, String text, String command) {
        return switch (command) {
            case "/guild" -> handled(() -> handleGuildCommand(chatId, text));
            case "/guildlist" -> handled(() -> send(chatId, formatAvailableRaids()));
            case "/rwf" -> handled(() -> sendRaceToWorldFirstStandings(chatId));
            case "/raidprogress" -> handled(() -> send(
                    chatId,
                    raidReportService.progress(raidReportPlayersByName(commandArguments(text)))
            ));
            case "/raidvault" -> handled(() -> send(
                    chatId,
                    raidReportService.weeklyVault(raidReportPlayersByName(commandArguments(text)))
            ));
            case "/raidcombat" -> handled(() -> send(
                    chatId,
                    warcraftLogsStatisticsService.raidCombatMessage(
                            raidReportPlayersByName(commandArguments(text))
                    )
            ));
            default -> false;
        };
    }

    void sendMenu(long chatId) {
        send(chatId, "Choose a raid report:", inlineKeyboard(List.of(
                inlineButton("World First", WOW_CALLBACK_PREFIX + WOW_COMMAND_CALLBACK + ":rwf"),
                inlineButton("Raid Progress", raidCallback(RAID_PROGRESS_CALLBACK)),
                inlineButton("Raid Vault", raidCallback(RAID_VAULT_CALLBACK)),
                inlineButton("Raid Combat", raidCallback(RAID_COMBAT_CALLBACK)),
                inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        )));
    }

    void handleCallback(long chatId, String callbackData) {
        String[] parts = callbackData.split(":");
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
                "All Profiles",
                raidCallback(action) + ":" + ALL_PROFILES_CALLBACK
        ));
        for (TrackedPlayer profile : profiles) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            buttons.add(inlineButton(
                    profile.profileName(),
                    raidCallback(action) + ":" + profile.profileId()
            ));
        }
        buttons.add(inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":raids"));
        send(chatId, "Choose a profile for " + raidActionLabel(action) + ":", inlineKeyboard(buttons));
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
        Long profileId = parseLong(profileIdValue);
        if (profileId == null) {
            return List.of();
        }
        return trackedPlayerService.activePlayers().stream()
                .filter(profile -> profile.profileId() == profileId)
                .findFirst()
                .map(List::of)
                .orElseGet(List::of);
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

    private void handleGuildCommand(long chatId, String text) {
        String argument = commandArguments(text);
        JsonNode guild = fetchDefaultGuild();
        if (argument.equalsIgnoreCase("list")) {
            send(chatId, formatRaidProgressionList(guild));
            return;
        }

        String raidKey = selectRaidKey(argument, guild);
        String lastCrawledAt = guild.path("last_crawled_at").asText("n/a");
        send(chatId, """
                %s
                %s
                Last update: %s
                """.formatted(
                defaultGuildProperties.guildName(),
                RaidProgressFormatter.formatRaidLine(guild, raidKey),
                formatLastCrawled(lastCrawledAt)
        ).strip());
    }

    private JsonNode fetchDefaultGuild() {
        return raiderIoClient.getGuildProfile(
                defaultGuildProperties.region(),
                defaultGuildProperties.realm(),
                defaultGuildProperties.guildName()
        );
    }

    private String selectRaidKey(String argument, JsonNode guild) {
        if (!argument.isBlank()) {
            return argument;
        }
        String configuredRaid = defaultGuildProperties.raidName();
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

    private static String formatLastCrawled(String isoUtc) {
        if (isoUtc == null || isoUtc.isBlank() || isoUtc.equals("n/a")) {
            return "n/a";
        }
        try {
            Instant instant = Instant.parse(isoUtc);
            ZonedDateTime zagreb = instant.atZone(ZAGREB_ZONE);
            return zagreb.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception _) {
            return "n/a";
        }
    }

    private static String commandArguments(String text) {
        String[] commandAndArguments = text.split("\\s+", 2);
        return commandAndArguments.length == 2 ? commandAndArguments[1].trim() : "";
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception _) {
            return null;
        }
    }

    private static String raidCallback(String action) {
        return WOW_CALLBACK_PREFIX + WOW_RAID_CALLBACK + ":" + action;
    }

    private static boolean isRaidAction(String action) {
        return RAID_PROGRESS_CALLBACK.equals(action)
                || RAID_VAULT_CALLBACK.equals(action)
                || RAID_COMBAT_CALLBACK.equals(action);
    }

    private static String raidActionLabel(String action) {
        return switch (action) {
            case RAID_PROGRESS_CALLBACK -> "Raid Progress";
            case RAID_VAULT_CALLBACK -> "Raid Vault";
            case RAID_COMBAT_CALLBACK -> "Raid Combat";
            default -> "Raid Report";
        };
    }

    private static InlineKeyboardButton inlineButton(String label, String callbackData) {
        return InlineKeyboardButton.builder().text(label).callbackData(callbackData).build();
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

    private void send(long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(long chatId, String text, InlineKeyboardMarkup keyboard) {
        messageSender.send(chatId, text, keyboard);
    }

    private static boolean handled(Runnable action) {
        action.run();
        return true;
    }

    @FunctionalInterface
    interface MessageSender {
        void send(long chatId, String text, InlineKeyboardMarkup keyboard);
    }
}
