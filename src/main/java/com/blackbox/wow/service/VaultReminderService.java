package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class VaultReminderService {

    private final RaiderIoClient raiderIoClient;
    private final TrackedPlayerService trackedPlayerService;
    private final BlackBoxBotNotifier notifier;
    private final boolean enabled;
    private final long reminderChatId;

    public VaultReminderService(
            RaiderIoClient raiderIoClient,
            TrackedPlayerService trackedPlayerService,
            BlackBoxBotNotifier notifier,
            @Value("${wow.vault-reminder.enabled:true}") boolean enabled,
            @Value("${wow.vault-reminder.chat-id:0}") long reminderChatId
    ) {
        this.raiderIoClient = raiderIoClient;
        this.trackedPlayerService = trackedPlayerService;
        this.notifier = notifier;
        this.enabled = enabled;
        this.reminderChatId = reminderChatId;
    }

    @Scheduled(
            cron = "${wow.vault-reminder.cron:0 0 18 * * TUE}",
            zone = "${wow.vault-reminder.zone:Europe/Zagreb}"
    )
    public void sendScheduledReminder() {
        if (!enabled || reminderChatId == 0) {
            log.debug("Vault reminder skipped because it is disabled or its chat ID is not configured.");
            return;
        }

        ReminderResult result = checkVaults();
        if (!result.missingPlayers().isEmpty()) {
            notifier.send(reminderChatId, formatMissingVaultMessage(result));
        } else if (!result.failedPlayers().isEmpty()) {
            log.warn("Vault reminder could not check: {}", String.join(", ", result.failedPlayers()));
        }
    }

    public String checkNowMessage() {
        ReminderResult result = checkVaults();
        if (!result.missingPlayers().isEmpty()) {
            return formatMissingVaultMessage(result);
        }
        if (!result.failedPlayers().isEmpty()) {
            return "No missing Mythic+ vault progress was found, but Raider.IO could not check: "
                    + String.join(", ", result.failedPlayers()) + ".";
        }
        return "All active Mythic+ vault profiles have at least one Mythic+ run recorded this week. "
                + "Delves and regular Mythic dungeons are not included.";
    }

    private ReminderResult checkVaults() {
        List<TrackedPlayer> missingPlayers = new ArrayList<>();
        List<String> failedPlayers = new ArrayList<>();

        for (TrackedPlayer player : trackedPlayerService.vaultWatchPlayers()) {
            try {
                var progress = raiderIoClient.getWeeklyVaultProgress(
                        player.region(),
                        player.realm(),
                        player.name()
                );
                if (progress.runs() == null || progress.runs().isEmpty()) {
                    missingPlayers.add(player);
                }
            } catch (Exception e) {
                failedPlayers.add(player.profileName());
                log.warn("Could not check weekly vault for profile {}: {}", player.profileName(), e.getMessage());
            }
        }

        return new ReminderResult(List.copyOf(missingPlayers), List.copyOf(failedPlayers));
    }

    private static String formatMissingVaultMessage(ReminderResult result) {
        StringBuilder sb = new StringBuilder("⚠️ Great Vault — Mythic+ only reminder\n")
                .append("Delves and regular Mythic dungeons are not included.\n\n")
                .append("The EU weekly reset is tomorrow. These players have no Mythic+ runs recorded:\n");
        for (TrackedPlayer player : result.missingPlayers()) {
            sb.append("• ").append(player.profileName());
            if (!player.profileName().equalsIgnoreCase(player.name())) {
                sb.append(" (").append(player.name()).append(")");
            }
            sb.append("\n");
        }
        sb.append("Complete at least one Mythic+ dungeon before the reset.");
        if (!result.failedPlayers().isEmpty()) {
            sb.append("\n\nCould not check: ").append(String.join(", ", result.failedPlayers())).append(".");
        }
        return sb.toString();
    }

    private record ReminderResult(List<TrackedPlayer> missingPlayers, List<String> failedPlayers) {
    }
}
