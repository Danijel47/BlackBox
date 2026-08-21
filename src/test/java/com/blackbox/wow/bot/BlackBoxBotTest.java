package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardMountService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.RaiderIoAbandonedRunService;
import com.blackbox.wow.service.RaceToWorldFirstService;
import com.blackbox.wow.service.MPlusDataCollectionService;
import com.blackbox.wow.service.MPlusProgressService;
import com.blackbox.wow.service.MPlusDungeonVaultService;
import com.blackbox.wow.service.MPlusPerformanceService;
import com.blackbox.wow.service.MPlusTeamService;
import com.blackbox.wow.service.MPlusAdvancedService;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TelegramAccessPolicy;
import com.blackbox.wow.service.TelegramBotUserService;
import com.blackbox.wow.service.TelegramDailyPromptService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.PlayerProfile;
import com.blackbox.wow.service.TrackedPlayerService.ProfileCharacter;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.VaultReminderService;
import com.blackbox.wow.service.WowTokenPriceHistoryService;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenHourAverage;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenPricePoint;
import com.blackbox.wow.service.WowTokenPriceHistoryService.TokenTradingHours;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlackBoxBotTest {

    @Mock private TelegramClient telegramClient;
    @Mock private RaiderIoClient raiderIoClient;
    @Mock private RaiderIoDefaultGuildProperties defaultGuildProperties;
    @Mock private WowWatchlistProperties watchlistProperties;
    @Mock private BlizzardAuctionService auctionService;
    @Mock private WowTokenPriceHistoryService tokenPriceHistoryService;
    @Mock private BlizzardItemService itemService;
    @Mock private BlizzardMountService mountService;
    @Mock private TimeToGoCommandService timeToGoCommandService;
    @Mock private RaiderIoAbandonedRunService abandonedRunService;
    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private VaultReminderService vaultReminderService;
    @Mock private RaceToWorldFirstService raceToWorldFirstService;
    @Mock private MPlusDataCollectionService mplusDataCollectionService;
    @Mock private MPlusProgressService mplusProgressService;
    @Mock private MPlusDungeonVaultService mplusDungeonVaultService;
    @Mock private MPlusPerformanceService mplusPerformanceService;
    @Mock private MPlusTeamService mplusTeamService;
    @Mock private MPlusAdvancedService mplusAdvancedService;
    @Mock private MPlusRunCorrelationService mplusRunCorrelationService;
    @Mock private WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    @Mock private TelegramAccessPolicy accessPolicy;
    @Mock private TelegramBotUserService telegramBotUserService;
    @Mock private TelegramDailyPromptService telegramDailyPromptService;

    private BlackBoxBot bot;

    @AfterEach
    void stopScheduler() {
        if (bot != null) {
            bot.stopWorkingMessageScheduler();
        }
    }

    @Test
    void routesVaultCommandWithBotMention() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/vault@BlackBoxBot");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.vaultWatchPlayers()).thenReturn(List.of());

        bot().consume(update);

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(message.capture());
        assertThat(message.getValue().getText()).isEqualTo("No Mythic+ vault watch players configured.");
    }

    @Test
    void ignoresCommandsFromUnauthorizedUsers() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/vault");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(false);

        bot().consume(update);

        verify(telegramClient, never()).execute(org.mockito.ArgumentMatchers.any(SendMessage.class));
    }

    @Test
    void forwardsEveryIncomingMessageSenderToTheDailyPrompt() {
        long chatId = 123L;
        long userId = 1_699_671_723L;

        bot().consume(update(chatId, userId, "hello"));

        verify(telegramDailyPromptService).onMessage(userId);
    }

    @Test
    void allowsAUserToSelectACharacterFromTheirOwnProfile() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/profile_main stormscale Alicemage");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        verify(trackedPlayerService).switchOwnedCharacter(userId, "stormscale", "Alicemage");
        assertThat(sentMessage().getText()).contains("Your selected main is now Alicemage-stormscale");
    }

    @Test
    void allowsTheAdminToLinkAProfileToATelegramUser() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        Update update = update(chatId, adminId, "/profile_link 456 Alice");
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);

        bot().consume(update);

        verify(trackedPlayerService).linkProfile(456L, "Alice");
        assertThat(sentMessage().getText()).contains("Profile Alice linked to Telegram user 456");
    }

    @Test
    void rejectsAnAdminProfileCommandFromARegularUser() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/profile_switch Alice eu stormscale Alicemage");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        verify(trackedPlayerService, never()).switchCharacter("Alice", "eu", "stormscale", "Alicemage");
        assertThat(sentMessage().getText()).contains("only be used by the configured bot administrator");
    }

    @Test
    void explainsHowAUserCanViewAndChangeTheirProfile() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/profile_help");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        assertThat(sentMessage().getText())
                .contains("/profile_main <realm> <character>")
                .contains("Only the characters registered to your profile can be selected")
                .contains("/mplus_combat");
    }

    @Test
    void showsOnlyTheProfileLinkedToTheRequestingUser() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/profile");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.profileForTelegramUser(userId)).thenReturn(Optional.of(new PlayerProfile(
                "Alice",
                userId,
                true,
                List.of(
                        new ProfileCharacter("eu", "stormscale", "Alicemage", true, true),
                        new ProfileCharacter("eu", "draenor", "Alicepriest", false, true)
                )
        )));

        bot().consume(update);

        assertThat(sentMessage().getText())
                .contains("Your player profile", "Alice", "→ Alicemage-stormscale", "Alicepriest-draenor");
    }

    @Test
    void letsTheAdminChangeAnyProfilesMain() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        Update update = update(chatId, adminId, "/profile_switch Alice eu stormscale Alicemage");
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);

        bot().consume(update);

        verify(trackedPlayerService).switchCharacter("Alice", "eu", "stormscale", "Alicemage");
        assertThat(sentMessage().getText()).contains("Profile Alice now uses Alicemage");
    }

    @Test
    void letsTheAdminDeleteACharacterFromAProfile() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        Update update = update(chatId, adminId, "/profile_char_delete Buco stormscale Bucomonk");
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);

        bot().consume(update);

        verify(trackedPlayerService).deleteCharacter("Buco", "stormscale", "Bucomonk");
        assertThat(sentMessage().getText()).contains("Character removed from the profile");
    }

    @Test
    void preventsARegularUserFromDeletingAProfileCharacter() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/profile_char_delete Buco stormscale Bucomonk");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        verify(trackedPlayerService, never()).deleteCharacter("Buco", "stormscale", "Bucomonk");
        assertThat(sentMessage().getText()).contains("only be used by the configured bot administrator");
    }

    @Test
    void showsTheRaceToWorldFirstStandings() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/rwf");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(raceToWorldFirstService.currentStandingsMessage()).thenReturn("RWF standings");

        bot().consume(update);

        verify(raceToWorldFirstService).currentStandingsMessage();
        assertThat(sentMessage().getText()).isEqualTo("RWF standings");
    }

    @Test
    void routesAnUnderscoreTravelCommandWithoutSeparateCityArguments() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/road_zadar_zagreb");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(timeToGoCommandService.formatCurrent("/road zadar zagreb")).thenReturn("Travel time");

        bot().consume(update);

        verify(timeToGoCommandService).formatCurrent("/road zadar zagreb");
        assertThat(sentMessage().getText()).isEqualTo("Travel time");
    }

    @Test
    void showsTheLowestTokenPriceFromTheLastWeek() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/token_lowest_week");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(tokenPriceHistoryService.lowestPriceSince(any(Instant.class))).thenReturn(Optional.of(
                new TokenPricePoint(3_456_789_000L, Instant.parse("2026-08-21T08:00:00Z"))
        ));

        bot().consume(update);

        assertThat(sentMessage().getText()).isEqualTo("""
                Lowest WoW Token price (EU) in the last week: 345678g 90s
                Date: 21 Aug 2026, 10:00 CEST
                """.strip());
    }

    @Test
    void explainsTheSupportedTokenHistoryPeriods() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/tokenlowest year");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        assertThat(sentMessage().getText())
                .isEqualTo("Usage: /token_lowest_week or /token_lowest_month");
        verify(tokenPriceHistoryService, never()).lowestPriceSince(any());
    }

    @Test
    void showsTheHighestTokenPriceFromTheLastMonth() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/token_highest_month");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(tokenPriceHistoryService.highestPriceSince(any(Instant.class))).thenReturn(Optional.of(
                new TokenPricePoint(4_100_000_000L, Instant.parse("2026-08-20T18:00:00Z"))
        ));

        bot().consume(update);

        assertThat(sentMessage().getText()).isEqualTo("""
                Highest WoW Token price (EU) in the last 30 days: 410000g 0s
                Date: 20 Aug 2026, 20:00 CEST
                """.strip());
    }

    @Test
    void showsTheBestRecurringHoursForBuyingAndSellingTokens() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/token_best");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(tokenPriceHistoryService.bestTradingHoursSince(any(Instant.class), any(ZoneId.class)))
                .thenReturn(Optional.of(new TokenTradingHours(
                        new TokenHourAverage(4, 3_200_000_000L, 28),
                        new TokenHourAverage(20, 3_600_000_000L, 29)
                )));

        bot().consume(update);

        assertThat(sentMessage().getText()).isEqualTo("""
                Best recurring WoW Token times (EU, last 30 days; Europe/Zagreb):
                Buy with gold: 04:00–04:59 — avg 320000g 0s (28 daily samples)
                Sell for gold: 20:00–20:59 — avg 360000g 0s (29 daily samples)
                Based on hourly averages; historical patterns do not guarantee future prices.
                """.strip());
    }

    @Test
    void reportsUnavailableTitleWatchWhenRaiderIoOmitsTheCutoffScore() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer player = new TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered");
        Update update = update(chatId, userId, "/title");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.titleWatchPlayers()).thenReturn(List.of(player));
        when(raiderIoClient.getCurrentMPlusTitleCutoff("eu")).thenReturn(
                new RaiderIoClient.MPlusTitleCutoff("eu", "season-mn-2", null, 0, "")
        );

        bot().consume(update);

        assertThat(sentMessage().getText()).contains("unavailable", "no cutoff score");
        verify(raiderIoClient, never()).getCurrentMPlusScore("eu", "Stormscale", "Bucothered");
    }

    @Test
    void reportsUnavailablePointOneTitleWatchWhenRaiderIoOmitsTheCutoffScore() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer player = new TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered");
        Update update = update(chatId, userId, "/title_01");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.titleZeroPointOneWatchPlayer()).thenReturn(Optional.of(player));
        when(raiderIoClient.getCurrentMPlusTitleCutoff("eu", "p999")).thenReturn(
                new RaiderIoClient.MPlusTitleCutoff("eu", "season-mn-2", null, 0, "")
        );

        bot().consume(update);

        assertThat(sentMessage().getText()).contains("unavailable", "no cutoff score");
        verify(raiderIoClient, never()).getCurrentMPlusScore("eu", "Stormscale", "Bucothered");
    }

    @Test
    void letsTheAdminViewMPlusCollectionStatus() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        Update update = update(chatId, adminId, "/mplus_status");
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);
        when(mplusDataCollectionService.statusMessage()).thenReturn("M+ collection status");
        when(warcraftLogsStatisticsService.seasonKey()).thenReturn("season-mn-2");
        when(mplusRunCorrelationService.statusMessage("season-mn-2")).thenReturn("Match status");

        bot().consume(update);

        verify(mplusDataCollectionService).statusMessage();
        assertThat(sentMessage().getText()).contains("M+ collection status", "Match status");
    }

    @Test
    void preventsARegularUserFromViewingMPlusCollectionStatus() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_status");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        verify(mplusDataCollectionService, never()).statusMessage();
        assertThat(sentMessage().getText()).contains("only be used by the configured bot administrator");
    }

    @Test
    void showsTheRequestingUsersMPlusProgress() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_progress@BlackBoxBot me");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusProgressService.progressMessage("me", userId)).thenReturn("Personal M+ progress");

        bot().consume(update);

        verify(mplusProgressService).progressMessage("me", userId);
        assertThat(sentMessage().getText()).isEqualTo("Personal M+ progress");
    }

    @Test
    void showsTheRequestingUsersDungeonCoverage() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_dungeons");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusDungeonVaultService.dungeonCoverageMessage("", userId))
                .thenReturn("Dungeon coverage");

        bot().consume(update);

        verify(mplusDungeonVaultService).dungeonCoverageMessage("", userId);
        assertThat(sentMessage().getText()).isEqualTo("Dungeon coverage");
    }

    @Test
    void showsAProfilesCurrentWeekVault() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_vault Lazo");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusDungeonVaultService.currentVaultMessage("Lazo", userId))
                .thenReturn("Current vault progress");

        bot().consume(update);

        verify(mplusDungeonVaultService).currentVaultMessage("Lazo", userId);
        assertThat(sentMessage().getText()).isEqualTo("Current vault progress");
    }

    @Test
    void showsAProfilesObservedMPlusHighlights() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_highlights Buco");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusPerformanceService.highlightsMessage("Buco", userId)).thenReturn("M+ highlights");

        bot().consume(update);

        verify(mplusPerformanceService).highlightsMessage("Buco", userId);
        assertThat(sentMessage().getText()).isEqualTo("M+ highlights");
    }

    @Test
    void routesTheUnifiedMPlusCommandToItsRequestedView() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_consistency Buco");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusAdvancedService.consistencyMessage("Buco", userId)).thenReturn("Consistency result");

        bot().consume(update);

        verify(mplusAdvancedService).consistencyMessage("Buco", userId);
        assertThat(sentMessage().getText()).isEqualTo("Consistency result");
    }

    private BlackBoxBot bot() {
        bot = new BlackBoxBot(
                "token",
                999L,
                telegramClient,
                raiderIoClient,
                defaultGuildProperties,
                watchlistProperties,
                auctionService,
                tokenPriceHistoryService,
                itemService,
                mountService,
                timeToGoCommandService,
                abandonedRunService,
                trackedPlayerService,
                vaultReminderService,
                raceToWorldFirstService,
                mplusDataCollectionService,
                mplusProgressService,
                mplusDungeonVaultService,
                mplusPerformanceService,
                mplusTeamService,
                mplusAdvancedService,
                mplusRunCorrelationService,
                warcraftLogsStatisticsService,
                accessPolicy,
                telegramBotUserService,
                telegramDailyPromptService
        );
        return bot;
    }

    private SendMessage sentMessage() throws Exception {
        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(message.capture());
        return message.getValue();
    }

    private static Update update(long chatId, long userId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getFrom()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        return update;
    }
}
