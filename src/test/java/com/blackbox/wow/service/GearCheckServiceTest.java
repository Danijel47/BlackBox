package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardEquipmentService;
import com.blackbox.wow.blizzard.BlizzardEquipmentService.EquipmentCheck;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GearCheckServiceTest {

    private static final TrackedPlayer MAIN = new TrackedPlayer(1, "Player", "eu", "Stormscale", "Mage");
    private static final TrackedPlayer ALT = new TrackedPlayer(1, "Player", "eu", "Stormscale", "Alt");
    private static final EquipmentCheck COMPLETE = new EquipmentCheck(8, List.of(), 2, 2, List.of(), null);
    private final TrackedPlayerService players = mock(TrackedPlayerService.class);
    private final BlizzardEquipmentService equipment = mock(BlizzardEquipmentService.class);
    private final GearCheckService service = new GearCheckService(players, equipment);

    @Test
    void handlesNoActiveMainsWithoutApiRequests() {
        when(players.activePlayers()).thenReturn(List.of());

        assertThat(service.messages()).containsExactly("No active group mains are configured.");
        verifyNoInteractions(equipment);
    }

    @Test
    void reportsMissingSlotsAndKnownSourceTime() {
        when(players.activePlayers()).thenReturn(List.of(MAIN));
        when(equipment.check(MAIN)).thenReturn(new EquipmentCheck(
                8, List.of("Head", "Legs"), 3, 1, List.of("Ring 1 (2)"), Instant.parse("2026-09-07T10:00:00Z")));

        assertThat(service.messages().getFirst()).contains(
                "Player — Mage-Stormscale", "Needs attention", "enchants 6/8, gems 1/3",
                "Missing enchants: Head, Legs", "Empty sockets: Ring 1 (2)", "2026-09-07T10:00:00Z");
    }

    @Test
    void isolatesUpstreamFailuresAndNeverExposesExceptionDetails() {
        when(players.activePlayers()).thenReturn(List.of(MAIN, ALT));
        when(equipment.check(MAIN)).thenThrow(new ResourceAccessException("secret-upstream-details"));
        when(equipment.check(ALT)).thenReturn(COMPLETE);

        assertThat(service.messages().getFirst())
                .contains("Mage-Stormscale\n❔ Unavailable", "Alt-Stormscale\n✅ Complete")
                .doesNotContain("secret-upstream-details");
    }

    @Test
    void usesTheNewlySelectedMainOnEachCommand() {
        when(players.activePlayers()).thenReturn(List.of(MAIN)).thenReturn(List.of(ALT));
        when(equipment.check(any())).thenReturn(COMPLETE);

        assertThat(service.messages().getFirst()).contains("Mage-Stormscale");
        assertThat(service.messages().getFirst()).contains("Alt-Stormscale").doesNotContain("Mage-Stormscale");
        verify(equipment).check(MAIN);
        verify(equipment).check(ALT);
    }

    @Test
    void splitsLargeGroupsIntoTelegramSizedMessages() {
        when(players.activePlayers()).thenReturn(IntStream.range(0, 100)
                .mapToObj(id -> new TrackedPlayer(id, "Player" + id, "eu", "Stormscale", "Mage" + id)).toList());
        when(equipment.check(any())).thenReturn(COMPLETE);

        List<String> messages = service.messages();

        assertThat(messages).hasSizeGreaterThan(1).allSatisfy(message -> assertThat(message.length()).isLessThan(4096));
        String report = String.join("\n", messages);
        for (int id = 0; id < 100; id++) {
            assertThat(report).containsOnlyOnce("Player" + id + " — Mage" + id + "-Stormscale");
        }
    }
}
