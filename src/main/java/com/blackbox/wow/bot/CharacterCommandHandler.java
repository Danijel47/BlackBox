package com.blackbox.wow.bot;

import com.blackbox.wow.blizzard.BlizzardItemLevelService;
import com.blackbox.wow.blizzard.BlizzardMountService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.service.GearUpgradeService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CharacterCommandHandler {

    private static final int INSURMOUNTABLE_COLLECTION_REQUIRED_MOUNTS = 600;
    private static final int INLINE_BUTTONS_PER_ROW = 2;
    private static final int MAX_PROFILE_BUTTONS = 90;
    private static final String WOW_CALLBACK_PREFIX = "wow:";
    private static final String WOW_MENU_CALLBACK = "menu";
    private static final String WOW_CHARACTER_CALLBACK = "character";
    private static final String ALL_PROFILES_CALLBACK = "all";
    private static final String ITEM_LEVEL_ACTION = "ilvl";
    private static final String ITEM_LEVEL_LABEL = "Item Level";
    private static final String ITEM_LEVEL_PREFIX = "Item level: ";
    private static final String NO_ACTIVE_PROFILES_MESSAGE = "No active profiles are available.";

    private final RaiderIoClient raiderIoClient;
    private final TrackedPlayerService trackedPlayerService;
    private final BlizzardMountService mountService;
    private final BlizzardItemLevelService itemLevelService;
    private final MessageSender messageSender;

    CharacterCommandHandler(
            RaiderIoClient raiderIoClient,
            TrackedPlayerService trackedPlayerService,
            BlizzardMountService mountService,
            BlizzardItemLevelService itemLevelService,
            MessageSender messageSender
    ) {
        this.raiderIoClient = raiderIoClient;
        this.trackedPlayerService = trackedPlayerService;
        this.mountService = mountService;
        this.itemLevelService = itemLevelService;
        this.messageSender = messageSender;
    }

    boolean handle(long chatId, String text, String command) {
        return switch (command) {
            case "/ilvl" -> handled(() -> sendProfileMenu(chatId, ITEM_LEVEL_ACTION));
            case "/mount-achiv" -> handled(() -> handleMountAchievement(chatId, text));
            case "/rio" -> handled(() -> handleRaiderIoCommand(chatId, text));
            default -> false;
        };
    }

    void sendMenu(long chatId) {
        send(chatId, "Choose a character report:", inlineKeyboard(List.of(
                inlineButton("Raider.IO Score", characterCallback("rio")),
                inlineButton(ITEM_LEVEL_LABEL, characterCallback(ITEM_LEVEL_ACTION)),
                inlineButton("Mount Progress", characterCallback("mount")),
                inlineButton("Enchants & Gems", characterCallback("gearcheck")),
                inlineButton(GearUpgradeService.LABEL, characterCallback(GearUpgradeService.ACTION)),
                inlineButton("Back", WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK)
        )));
    }

    void sendProfileMenu(long chatId, String actionKey, String label) {
        List<TrackedPlayer> profiles = trackedPlayerService.activePlayers();
        if (profiles.isEmpty()) {
            send(chatId, NO_ACTIVE_PROFILES_MESSAGE);
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(inlineButton(
                "All Profiles",
                characterCallback(actionKey) + ":" + ALL_PROFILES_CALLBACK
        ));
        for (TrackedPlayer profile : profiles) {
            if (buttons.size() >= MAX_PROFILE_BUTTONS) {
                break;
            }
            buttons.add(inlineButton(
                    profile.profileName(),
                    characterCallback(actionKey) + ":" + profile.profileId()
            ));
        }
        buttons.add(inlineButton(
                "Back",
                WOW_CALLBACK_PREFIX + WOW_MENU_CALLBACK + ":" + WOW_CHARACTER_CALLBACK
        ));
        send(chatId, "Choose a profile for " + label + ":", inlineKeyboard(buttons));
    }

    void handleCallback(long chatId, String callbackData) {
        String[] parts = callbackData.split(":");
        if (parts.length == 3 && CharacterReportAction.isSupported(parts[2])) {
            sendProfileMenu(chatId, parts[2]);
            return;
        }
        if (parts.length == 4) {
            runCharacterReport(chatId, parts[2], parts[3]);
            return;
        }
        send(chatId, "That character report is no longer valid. Use /wow to start again.");
    }

    private void sendProfileMenu(long chatId, String actionKey) {
        CharacterReportAction action = CharacterReportAction.fromKey(actionKey);
        if (action == null) {
            send(chatId, "That character report is unavailable.");
            return;
        }
        sendProfileMenu(chatId, action.key(), action.label());
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
            case ITEM_LEVEL -> send(chatId, formatItemLevel(loadCharacterReportRow(action, profile)));
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
                case RAIDER_IO -> new CharacterReportRow(
                        profile,
                        raiderIoClient.getCurrentMPlusScore(profile.region(), profile.realm(), profile.name()),
                        null,
                        null
                );
                case ITEM_LEVEL -> new CharacterReportRow(
                        profile,
                        null,
                        null,
                        itemLevelService.equippedItemLevel(profile)
                );
                case MOUNTS -> new CharacterReportRow(
                        profile,
                        null,
                        mountService.getMountProgress(profile.realm(), profile.name()),
                        null
                );
            };
        } catch (RuntimeException _) {
            return new CharacterReportRow(profile, null, null, null);
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

    private static void appendCharacterReport(
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
            case ITEM_LEVEL -> message.append("• ").append(profile.profileName()).append(" (")
                    .append(profile.name()).append('-').append(profile.realm()).append(")\n")
                    .append("  ").append(ITEM_LEVEL_PREFIX)
                    .append(valueOrUnavailable(row.itemLevel())).append("\n\n");
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

    private void handleRaiderIoCommand(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 4) {
            send(chatId, """
                    Usage: /rio <region> <realm> <name>
                    Example: /rio eu stormscale bucothered
                    """.strip());
            return;
        }

        String region = parts[1].toLowerCase(Locale.ROOT);
        String realm = parts[2];
        String name = parts[3];
        try {
            send(chatId, formatRaiderIoScore(raiderIoClient.getCurrentMPlusScore(region, realm, name)));
        } catch (Exception e) {
            send(chatId, """
                    Couldn’t fetch Raider.IO for %s/%s/%s
                    Reason: %s
                    """.formatted(region, realm, name, e.getMessage()).strip());
        }
    }

    private void handleMountAchievement(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 3) {
            send(chatId, """
                    Usage: /mount_achievement <realm> <name>
                    Example: /mount_achievement stormscale bucothered
                    """.strip());
            return;
        }

        try {
            var progress = mountService.getMountProgress(parts[1], parts[2]);
            send(chatId, "Insurmountable Collection: " + formatMountAchievementProgress(progress.usable()));
        } catch (Exception e) {
            send(chatId, formatMountLookupError(parts[1], parts[2], e));
        }
    }

    private TrackedPlayer findActiveProfile(String profileIdValue) {
        Long profileId = parseLong(profileIdValue);
        if (profileId == null) {
            return null;
        }
        return trackedPlayerService.activePlayers().stream()
                .filter(profile -> profile.profileId() == profileId)
                .findFirst()
                .orElse(null);
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

    private static String formatItemLevel(CharacterReportRow row) {
        TrackedPlayer profile = row.profile();
        return """
                %s
                %s - %s (%s)
                Equipped item level: %s
                """.formatted(
                ITEM_LEVEL_LABEL,
                profile.name(),
                profile.realm(),
                profile.region(),
                row.itemLevel() == null ? "unavailable" : row.itemLevel().toString()
        ).strip();
    }

    private static String valueOrUnavailable(BigDecimal value) {
        return value == null ? "n/a" : value.toString();
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

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception _) {
            return null;
        }
    }

    private static String characterCallback(String action) {
        return WOW_CALLBACK_PREFIX + WOW_CHARACTER_CALLBACK + ":" + action;
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

    private enum CharacterReportAction {
        RAIDER_IO("rio", "Raider.IO Score"),
        ITEM_LEVEL(ITEM_LEVEL_ACTION, ITEM_LEVEL_LABEL),
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
            BlizzardMountService.MountProgress mountProgress,
            BigDecimal itemLevel
    ) {
        private boolean unavailable() {
            return score == null && mountProgress == null && itemLevel == null;
        }

        private BigDecimal metric(CharacterReportAction action) {
            return switch (action) {
                case RAIDER_IO -> score == null ? null : score.all();
                case ITEM_LEVEL -> itemLevel;
                case MOUNTS -> mountProgress == null ? null : BigDecimal.valueOf(mountProgress.usable());
            };
        }
    }

    @FunctionalInterface
    interface MessageSender {
        void send(long chatId, String text, InlineKeyboardMarkup keyboard);
    }
}
