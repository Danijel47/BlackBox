package com.blackbox.wow.bot;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.service.RaceToWorldFirstService;
import com.blackbox.wow.service.RaidReportService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RaidCommandHandlerTest {

    private static final long CHAT_ID = 123L;

    @Mock private RaiderIoClient raiderIoClient;
    @Mock private RaiderIoDefaultGuildProperties defaultGuildProperties;
    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private RaceToWorldFirstService raceToWorldFirstService;
    @Mock private RaidReportService raidReportService;
    @Mock private WarcraftLogsStatisticsService warcraftLogsStatisticsService;

    @Test
    void namedRaidCommandSelectsOnlyTheRequestedProfile() {
        List<SentMessage> messages = new ArrayList<>();
        TrackedPlayer buco = player(11L, "Buco");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, player(12L, "Linq")));
        when(warcraftLogsStatisticsService.raidCombatMessage(List.of(buco))).thenReturn("Buco raid combat");

        boolean handled = handler(messages).handle(CHAT_ID, "/raidcombat buco", "/raidcombat");

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Buco raid combat", null));
    }

    @Test
    void blankRaidCommandUsesAllActiveProfiles() {
        List<SentMessage> messages = new ArrayList<>();
        List<TrackedPlayer> players = List.of(player(11L, "Buco"), player(12L, "Linq"));
        when(trackedPlayerService.activePlayers()).thenReturn(players);
        when(raidReportService.progress(players)).thenReturn("All raid progress");

        handler(messages).handle(CHAT_ID, "/raidprogress", "/raidprogress");

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "All raid progress", null));
    }

    @Test
    void guildCommandUsesConfiguredGuildAndRequestedRaid() throws Exception {
        List<SentMessage> messages = new ArrayList<>();
        when(defaultGuildProperties.region()).thenReturn("eu");
        when(defaultGuildProperties.realm()).thenReturn("stormscale");
        when(defaultGuildProperties.guildName()).thenReturn("BlackBox");
        when(raiderIoClient.getGuildProfile("eu", "stormscale", "BlackBox"))
                .thenReturn(new ObjectMapper().readTree("""
                        {
                          "last_crawled_at": "2026-01-01T12:00:00Z",
                          "raid_progression": {
                            "midnight": {
                              "total_bosses": 8,
                              "heroic_bosses_killed": 7,
                              "mythic_bosses_killed": 2
                            }
                          }
                        }
                        """));

        handler(messages).handle(CHAT_ID, "/guild midnight", "/guild");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "BlackBox\nmidnight: 7/8H, 2/8M\nLast update: 2026-01-01 13:00:00",
                null
        ));
    }

    @Test
    void raceToWorldFirstFailureUsesTheExistingSafeMessage() {
        List<SentMessage> messages = new ArrayList<>();
        when(raceToWorldFirstService.currentStandingsMessage()).thenThrow(new IllegalStateException("secret"));

        handler(messages).handle(CHAT_ID, "/rwf", "/rwf");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Could not fetch the Race to World First standings from Raider.IO.",
                null
        ));
    }

    @Test
    void raidMenuPreservesEveryCallbackIdentifier() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).sendMenu(CHAT_ID);

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.text()).isEqualTo("Choose a raid report:");
            assertThat(buttons(message.keyboard()))
                    .extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("World First", "wow:command:rwf"),
                            org.assertj.core.groups.Tuple.tuple("Raid Progress", "wow:raid:progress"),
                            org.assertj.core.groups.Tuple.tuple("Raid Vault", "wow:raid:vault"),
                            org.assertj.core.groups.Tuple.tuple("Raid Combat", "wow:raid:combat"),
                            org.assertj.core.groups.Tuple.tuple("Back", "wow:menu")
                    );
        });
    }

    @Test
    void raidProfilePickerIncludesAllProfilesAndCapsIndividualButtons() {
        List<SentMessage> messages = new ArrayList<>();
        List<TrackedPlayer> players = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> player(index, "Profile" + index))
                .toList();
        when(trackedPlayerService.activePlayers()).thenReturn(players);

        handler(messages).handleCallback(CHAT_ID, "wow:raid:progress");

        List<InlineKeyboardButton> buttons = buttons(messages.getFirst().keyboard());
        assertThat(buttons).hasSize(91);
        assertThat(buttons.getFirst().getCallbackData()).isEqualTo("wow:raid:progress:all");
        assertThat(buttons.get(89).getCallbackData()).isEqualTo("wow:raid:progress:89");
        assertThat(buttons.getLast().getCallbackData()).isEqualTo("wow:menu:raids");
    }

    @Test
    void selectedProfileCallbackRunsTheRequestedReport() {
        List<SentMessage> messages = new ArrayList<>();
        TrackedPlayer buco = player(11L, "Buco");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, player(12L, "Linq")));
        when(raidReportService.weeklyVault(List.of(buco))).thenReturn("Buco raid vault");

        handler(messages).handleCallback(CHAT_ID, "wow:raid:vault:11");

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Buco raid vault", null));
        verify(raidReportService).weeklyVault(List.of(buco));
    }

    @Test
    void allProfilesCallbackRunsWarcraftLogsCombatReport() {
        List<SentMessage> messages = new ArrayList<>();
        List<TrackedPlayer> players = List.of(player(11L, "Buco"));
        when(trackedPlayerService.activePlayers()).thenReturn(players);
        when(warcraftLogsStatisticsService.raidCombatMessage(players)).thenReturn("Raid combat");

        handler(messages).handleCallback(CHAT_ID, "wow:raid:combat:all");

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Raid combat", null));
    }

    @Test
    void invalidRaidCallbackReturnsTheExistingRecoveryMessage() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handleCallback(CHAT_ID, "wow:raid:unknown");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "That raid report is no longer available. Use /wow to refresh the menu.",
                null
        ));
        verifyNoInteractions(trackedPlayerService, raidReportService, warcraftLogsStatisticsService);
    }

    @Test
    void unknownCommandIsLeftForTheNextHandler() {
        List<SentMessage> messages = new ArrayList<>();

        boolean handled = handler(messages).handle(CHAT_ID, "/unknown", "/unknown");

        assertThat(handled).isFalse();
        assertThat(messages).isEmpty();
        verifyNoInteractions(
                raiderIoClient,
                trackedPlayerService,
                raceToWorldFirstService,
                raidReportService,
                warcraftLogsStatisticsService
        );
    }

    private RaidCommandHandler handler(List<SentMessage> messages) {
        return new RaidCommandHandler(
                raiderIoClient,
                defaultGuildProperties,
                trackedPlayerService,
                raceToWorldFirstService,
                raidReportService,
                warcraftLogsStatisticsService,
                (chatId, text, keyboard) -> messages.add(new SentMessage(chatId, text, keyboard))
        );
    }

    private static TrackedPlayer player(long id, String profileName) {
        return new TrackedPlayer(id, profileName, "eu", "stormscale", profileName + "char");
    }

    private static List<InlineKeyboardButton> buttons(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream().flatMap(List::stream).toList();
    }

    private record SentMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
    }
}
