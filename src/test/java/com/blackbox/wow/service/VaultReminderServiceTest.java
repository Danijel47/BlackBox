package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.WeeklyVaultProgress;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultReminderServiceTest {

    @Mock
    private RaiderIoClient raiderIoClient;

    @Mock
    private TrackedPlayerService trackedPlayerService;

    @Mock
    private BlackBoxBotNotifier notifier;

    @Test
    void identifiesScheduledReminderAsMythicPlusOnly() {
        TrackedPlayer player = new TrackedPlayer(1L, "Alice", "eu", "stormscale", "Alicechar");
        when(trackedPlayerService.vaultWatchPlayers()).thenReturn(List.of(player));
        when(raiderIoClient.getWeeklyVaultProgress("eu", "stormscale", "Alicechar"))
                .thenReturn(new WeeklyVaultProgress(
                        "Alicechar",
                        "Stormscale",
                        "eu",
                        List.of(),
                        "https://raider.io/characters/eu/stormscale/Alicechar"
                ));

        String message = service().checkNowMessage();

        assertThat(message)
                .startsWith("⚠️ Great Vault — Mythic+ only reminder")
                .contains("Delves and regular Mythic dungeons are not included.")
                .contains("Alice (Alicechar)");
    }

    @Test
    void identifiesSuccessfulCheckAsMythicPlusOnly() {
        TrackedPlayer player = new TrackedPlayer(1L, "Alice", "eu", "stormscale", "Alicechar");
        when(trackedPlayerService.vaultWatchPlayers()).thenReturn(List.of(player));
        when(raiderIoClient.getWeeklyVaultProgress("eu", "stormscale", "Alicechar"))
                .thenReturn(new WeeklyVaultProgress(
                        "Alicechar",
                        "Stormscale",
                        "eu",
                        List.of(new RaiderIoClient.MPlusRun(4, "NPX", "2026-08-16T10:00:00Z")),
                        "https://raider.io/characters/eu/stormscale/Alicechar"
                ));

        assertThat(service().checkNowMessage())
                .contains("Mythic+ vault profiles")
                .contains("Delves and regular Mythic dungeons are not included.");
    }

    private VaultReminderService service() {
        return new VaultReminderService(raiderIoClient, trackedPlayerService, notifier, true, 123L);
    }
}
