package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardMountService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.RaceToWorldFirstService;
import com.blackbox.wow.service.RaidReportService;
import com.blackbox.wow.service.MPlusDataCollectionService;
import com.blackbox.wow.service.MPlusProgressService;
import com.blackbox.wow.service.MPlusDungeonVaultService;
import com.blackbox.wow.service.MPlusPerformanceService;
import com.blackbox.wow.service.MPlusTeamService;
import com.blackbox.wow.service.MPlusTitleWatchService;
import com.blackbox.wow.service.HousingSalesReportService;
import com.blackbox.wow.service.HousingMarketUnavailableException;
import com.blackbox.wow.service.MPlusAdvancedService;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.MPlusSeasonReportService;
import com.blackbox.wow.service.TelegramAccessPolicy;
import com.blackbox.wow.service.TelegramBotUserService;
import com.blackbox.wow.service.TelegramDailyPromptService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.PlayerProfile;
import com.blackbox.wow.service.TrackedPlayerService.ProfileCharacter;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.VaultReminderService;
import com.blackbox.wow.service.WowTokenReportService;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.blackbox.wow.service.HousingMarketUnavailableException.DataSource.TSM_EU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlackBoxBotTest {

    @Mock private TelegramClient telegramClient;
    @Mock private RaiderIoClient raiderIoClient;
    @Mock private RaiderIoDefaultGuildProperties defaultGuildProperties;
    @Mock private WowWatchlistProperties watchlistProperties;
    @Mock private BlizzardAuctionService auctionService;
    @Mock private WowTokenReportService wowTokenReportService;
    @Mock private BlizzardItemService itemService;
    @Mock private BlizzardMountService mountService;
    @Mock private TimeToGoCommandService timeToGoCommandService;
    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private VaultReminderService vaultReminderService;
    @Mock private RaceToWorldFirstService raceToWorldFirstService;
    @Mock private RaidReportService raidReportService;
    @Mock private MPlusDataCollectionService mplusDataCollectionService;
    @Mock private MPlusProgressService mplusProgressService;
    @Mock private MPlusDungeonVaultService mplusDungeonVaultService;
    @Mock private MPlusPerformanceService mplusPerformanceService;
    @Mock private MPlusTeamService mplusTeamService;
    @Mock private MPlusAdvancedService mplusAdvancedService;
    @Mock private MPlusRunCorrelationService mplusRunCorrelationService;
    @Mock private MPlusSeasonReportService mplusSeasonReportService;
    @Mock private MPlusTitleWatchService mplusTitleWatchService;
    @Mock private WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    @Mock private TelegramAccessPolicy accessPolicy;
    @Mock private TelegramBotUserService telegramBotUserService;
    @Mock private TelegramDailyPromptService telegramDailyPromptService;
    @Mock private HousingSalesReportService housingSalesReportService;

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
        when(mplusSeasonReportService.weeklyVaultWatch())
                .thenReturn("No Mythic+ vault watch players configured.");

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
    void allowsOnlyTheAdminToRequestHousingSales() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);
        when(housingSalesReportService.topSellingMessage()).thenReturn("Housing sales report");

        bot().consume(update(chatId, adminId, "/housing_top"));

        verify(housingSalesReportService).topSellingMessage();
        assertThat(sentMessage().getText()).isEqualTo("Housing sales report");
    }

    @Test
    void rejectsHousingSalesForAnAllowedNonAdminUser() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update(chatId, userId, "/housing_top"));

        verify(housingSalesReportService, never()).topSellingMessage();
        assertThat(sentMessage().getText()).contains("only be used by the configured bot administrator");
    }

    @Test
    void reportsTheUnavailableHousingDataSourceToTheAdmin() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);
        when(housingSalesReportService.topSellingMessage()).thenThrow(
                HousingMarketUnavailableException.from(
                        TSM_EU,
                        HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null)
                )
        );

        bot().consume(update(chatId, adminId, "/housing_top"));

        assertThat(sentMessage().getText())
                .isEqualTo("Housing sales data is temporarily unavailable: TSM EU market data returned HTTP 403.");
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
                .contains("/mplus_combat")
                .doesNotContain("/mplus_combat_12");
    }

    @Test
    void showsOnlyTheProfileLinkedToTheRequestingUser() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/profile");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.profileForTelegramUser(userId)).thenReturn(Optional.of(new PlayerProfile(
                1L,
                "Alice",
                userId,
                true,
                List.of(
                        new ProfileCharacter(10L, "eu", "stormscale", "Alicemage", true, true),
                        new ProfileCharacter(11L, "eu", "draenor", "Alicepriest", false, true),
                        new ProfileCharacter(12L, "eu", "silvermoon", "Hiddenalt", false, false)
                )
        )));

        bot().consume(update);

        assertThat(sentMessage().getText())
                .contains("Your player profile", "Alice", "→ Alicemage-stormscale", "Alicepriest-draenor")
                .doesNotContain("Hiddenalt");
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
        when(wowTokenReportService.lowestPrice(any(), any())).thenReturn("Lowest token price");

        bot().consume(update);

        verify(wowTokenReportService).lowestPrice(any(), any());
        assertThat(sentMessage().getText()).isEqualTo("Lowest token price");
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
        verify(wowTokenReportService, never()).lowestPrice(any(), any());
    }

    @Test
    void showsTheHighestTokenPriceFromTheLastMonth() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/token_highest_month");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(wowTokenReportService.highestPrice(any(), any())).thenReturn("Highest token price");

        bot().consume(update);

        verify(wowTokenReportService).highestPrice(any(), any());
        assertThat(sentMessage().getText()).isEqualTo("Highest token price");
    }

    @Test
    void showsTheBestRecurringHoursForBuyingAndSellingTokens() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/token_best");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(wowTokenReportService.bestTradingHours()).thenReturn("Best recurring token hours");

        bot().consume(update);

        verify(wowTokenReportService).bestTradingHours();
        assertThat(sentMessage().getText()).isEqualTo("Best recurring token hours");
    }

    @Test
    void routesTheTitleWatchCommand() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/title");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusTitleWatchService.onePercentReport()).thenReturn("1% title watch");

        bot().consume(update);

        verify(mplusTitleWatchService).onePercentReport();
        assertThat(sentMessage().getText()).isEqualTo("1% title watch");
    }

    @Test
    void routesThePointOneTitleWatchCommand() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/title01");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(mplusTitleWatchService.pointOnePercentReport()).thenReturn("0.1% title watch");

        bot().consume(update);

        verify(mplusTitleWatchService).pointOnePercentReport();
        assertThat(sentMessage().getText()).isEqualTo("0.1% title watch");
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
    void runsTheConfiguredCombatCommandForTheRequestedProfile() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_combat Buco");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(warcraftLogsStatisticsService.combatMessage("Buco")).thenReturn("Buco configured combat");

        bot().consume(update);

        verify(warcraftLogsStatisticsService).combatMessage("Buco");
        assertThat(sentMessage().getText()).isEqualTo("Buco configured combat");
    }

    @Test
    void showsMPlusReportsAsInlineButtons() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update);

        SendMessage message = sentMessage();
        assertThat(message.getText()).isEqualTo("Choose a Mythic+ report:");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .contains(
                        "Progress", "Dungeons", "Vault", "Performance", "Pair", "Awards",
                        "Combat"
                )
                .doesNotContain("Combat +12")
                .doesNotContain("Status");
    }

    @Test
    void showsThePublicWowSectionsAsInlineButtons() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update(chatId, userId, "/wow"));

        SendMessage message = sentMessage();
        assertThat(message.getText()).isEqualTo("Choose a WoW section:");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .containsExactly(
                        "Mythic+", "Profiles", "Character", "Raids",
                        "Season", "Tokens", "Materials"
                );
    }

    @Test
    void showsOnlyNonAdminProfileActionsToARegularUser() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update(chatId, userId, "/profiles"));

        SendMessage message = sentMessage();
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .containsExactly("My Profile", "Select Main", "Group Mains", "Back");
    }

    @Test
    void keepsVaultOnlyInTheMythicPlusMenu() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(callbackUpdate(chatId, userId, "wow:menu:character"));

        InlineKeyboardMarkup characterKeyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(characterKeyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .containsExactly("Raider.IO Score", "Item Level", "Mount Progress", "Back")
                .doesNotContain("Weekly Vault");
    }

    @Test
    void offersAllProfilesForCharacterReports() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered")
        ));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:rio"));

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getCallbackData()))
                .contains("wow:character:rio:all", "wow:character:rio:11");
    }

    @Test
    void offersAllAndIndividualProfilesForItemLevel() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered")
        ));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:ilvl"));

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getCallbackData()))
                .contains("wow:character:ilvl:all", "wow:character:ilvl:11");
    }

    @Test
    void runsItemLevelForSelectedProfile() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer buco = new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco));
        when(raiderIoClient.getCurrentMPlusScore("eu", "stormscale", "Bucothered"))
                .thenReturn(new RaiderIoClient.RaiderIoScore(
                        "Bucothered", "Stormscale", "eu",
                        new BigDecimal("303.25"),
                        null, null, null, null, "", Instant.EPOCH
                ));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:ilvl:11"));

        assertThat(sentMessage().getText())
                .contains("Item Level", "Bucothered - Stormscale (eu)", "Equipped item level: 303.25")
                .doesNotContain("Score:", "DPS:");
    }

    @Test
    void runsItemLevelForAllProfiles() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer buco = new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered");
        TrackedPlayer linq = new TrackedPlayer(12L, "Linq", "eu", "draenor", "Thelinq");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(raiderIoClient.getCurrentMPlusScore("eu", "stormscale", "Bucothered"))
                .thenReturn(new RaiderIoClient.RaiderIoScore(
                        "Bucothered", "Stormscale", "eu",
                        new BigDecimal("303.25"),
                        null, null, null, null, "", Instant.EPOCH
                ));
        when(raiderIoClient.getCurrentMPlusScore("eu", "draenor", "Thelinq"))
                .thenReturn(new RaiderIoClient.RaiderIoScore(
                        "Thelinq", "Draenor", "eu",
                        new BigDecimal("306"),
                        null, null, null, null, "", Instant.EPOCH
                ));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:ilvl:all"));

        String report = sentMessage().getText();
        assertThat(report)
                .contains("Item Level — all profiles")
                .contains("• Buco (Bucothered-Stormscale)", "Item level: 303.25")
                .contains("• Linq (Thelinq-Draenor)", "Item level: 306")
                .doesNotContain("Score:", "DPS:");
        assertThat(report.indexOf("• Linq")).isLessThan(report.indexOf("• Buco"));
    }

    @Test
    void runsRaiderIoScoreForAllProfilesFromTheButton() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer buco = new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered");
        TrackedPlayer linq = new TrackedPlayer(12L, "Linq", "eu", "draenor", "Thelinq");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(raiderIoClient.getCurrentMPlusScore("eu", "stormscale", "Bucothered"))
                .thenReturn(new RaiderIoClient.RaiderIoScore(
                        "Bucothered", "Stormscale", "eu",
                        new BigDecimal("303"),
                        new BigDecimal("3210.5"), new BigDecimal("3210.5"),
                        null, null, "", Instant.EPOCH
                ));
        when(raiderIoClient.getCurrentMPlusScore("eu", "draenor", "Thelinq"))
                .thenReturn(new RaiderIoClient.RaiderIoScore(
                        "Thelinq", "Draenor", "eu",
                        new BigDecimal("306"),
                        new BigDecimal("3300"), null,
                        new BigDecimal("3300"), null, "", Instant.EPOCH
                ));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:rio:all"));

        String report = sentMessage().getText();
        assertThat(report)
                .contains("Raider.IO Score — all profiles")
                .contains("• Buco (Bucothered-Stormscale)", "Item level: 303", "Score: 3210.5", "DPS: 3210.5")
                .contains("• Linq (Thelinq-Draenor)", "Item level: 306", "Score: 3300", "Healer: 3300");
        assertThat(report.indexOf("• Linq")).isLessThan(report.indexOf("• Buco"));
    }

    @Test
    void sortsAllProfilesByUsableMountCount() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer buco = new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered");
        TrackedPlayer linq = new TrackedPlayer(12L, "Linq", "eu", "draenor", "Thelinq");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(mountService.getMountProgress("stormscale", "Bucothered"))
                .thenReturn(new BlizzardMountService.MountProgress("Bucothered", "stormscale", 500, 550));
        when(mountService.getMountProgress("draenor", "Thelinq"))
                .thenReturn(new BlizzardMountService.MountProgress("Thelinq", "draenor", 600, 650));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:mount:all"));

        String report = sentMessage().getText();
        assertThat(report).contains("Mount Progress — all profiles", "Usable mounts: 600", "Usable mounts: 500");
        assertThat(report.indexOf("• Linq")).isLessThan(report.indexOf("• Buco"));
    }

    @Test
    void includesCurrentItemLevelInIndividualRaiderIoReport() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer buco = new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco));
        when(raiderIoClient.getCurrentMPlusScore("eu", "stormscale", "Bucothered"))
                .thenReturn(new RaiderIoClient.RaiderIoScore(
                        "Bucothered", "Stormscale", "eu",
                        new BigDecimal("303.25"),
                        new BigDecimal("3210.5"), new BigDecimal("3210.5"),
                        null, null, "", Instant.EPOCH
                ));

        bot().consume(callbackUpdate(chatId, userId, "wow:character:rio:11"));

        assertThat(sentMessage().getText())
                .contains("Raider.IO (current season)", "Bucothered - Stormscale (eu)")
                .contains("Item level: 303.25", "Score: 3210.5", "DPS: 3210.5");
    }

    @Test
    void offersProfileRaidReportsWithoutGuildProgress() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(callbackUpdate(chatId, userId, "wow:menu:raids"));

        InlineKeyboardMarkup raidKeyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(raidKeyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .containsExactly("World First", "Raid Progress", "Raid Vault", "Raid Combat", "Back")
                .doesNotContain("Guild Progress", "Raid List", "Affixes");
    }

    @Test
    void offersAllAndIndividualProfilesForRaidProgress() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered"),
                new TrackedPlayer(12L, "Linq", "eu", "draenor", "Thelinq")
        ));

        bot().consume(callbackUpdate(chatId, userId, "wow:raid:progress"));

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .containsExactly("All Profiles", "Buco", "Linq", "Back");
    }

    @Test
    void runsRaidCombatForAllProfiles() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        List<TrackedPlayer> profiles = List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered")
        );
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(profiles);
        when(warcraftLogsStatisticsService.raidCombatMessage(profiles)).thenReturn("Raid parses");

        bot().consume(callbackUpdate(chatId, userId, "wow:raid:combat:all"));

        verify(warcraftLogsStatisticsService).raidCombatMessage(profiles);
        assertThat(sentMessage().getText()).isEqualTo("Raid parses");
    }

    @Test
    void runsRaidCombatForNamedProfileCommand() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        TrackedPlayer buco = new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                buco,
                new TrackedPlayer(12L, "Linq", "eu", "draenor", "Thelinq")
        ));
        when(warcraftLogsStatisticsService.raidCombatMessage(List.of(buco))).thenReturn("Buco raid parses");

        bot().consume(update(chatId, userId, "/raid_combat Buco"));

        verify(warcraftLogsStatisticsService).raidCombatMessage(List.of(buco));
        assertThat(sentMessage().getText()).isEqualTo("Buco raid parses");
    }

    @Test
    void usesWarcraftLogsCombatMetricsForMPlusAwards() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/mplus_awards");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(warcraftLogsStatisticsService.awardsMessage()).thenReturn("Readable M+ awards");

        bot().consume(update);

        verify(warcraftLogsStatisticsService).awardsMessage();
        assertThat(sentMessage().getText()).isEqualTo("Readable M+ awards");
    }

    @Test
    void removesInlineButtonsAfterAnAuthorizedSelection() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        int messageId = 789;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(callbackUpdate(chatId, userId, "mplus:menu", messageId));

        ArgumentCaptor<EditMessageReplyMarkup> edit = ArgumentCaptor.forClass(EditMessageReplyMarkup.class);
        verify(telegramClient).execute(edit.capture());
        assertThat(edit.getValue().getChatId()).isEqualTo(Long.toString(chatId));
        assertThat(edit.getValue().getMessageId()).isEqualTo(messageId);
        assertThat(edit.getValue().getReplyMarkup()).isNull();
    }

    @Test
    void letsAUserSelectTheirMainThroughProfileButtons() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.profileForTelegramUser(userId)).thenReturn(Optional.of(new PlayerProfile(
                1L,
                "Alice",
                userId,
                true,
                List.of(
                        new ProfileCharacter(10L, "eu", "stormscale", "Alicemage", true, true),
                        new ProfileCharacter(11L, "eu", "draenor", "Alicepriest", false, true)
                )
        )));

        bot().consume(callbackUpdate(chatId, userId, "wow:profiles:select:456:1"));

        verify(trackedPlayerService).switchOwnedCharacter(userId, "draenor", "Alicepriest");
        assertThat(sentMessage().getText()).contains("Alicepriest-draenor");
    }

    @Test
    void preventsAnotherUserFromChangingAProfileMain() throws Exception {
        long chatId = 123L;
        long profileOwnerId = 456L;
        long otherUserId = 654L;
        when(accessPolicy.isAllowed(chatId, otherUserId)).thenReturn(true);

        bot().consume(callbackUpdate(
                chatId,
                otherUserId,
                "wow:profiles:select:" + profileOwnerId + ":1"
        ));

        verify(trackedPlayerService, never()).switchOwnedCharacter(any(Long.class), any(), any());
        assertThat(sentMessage().getText())
                .isEqualTo("You can’t change another player’s main character. Open /profiles to choose your own.");
    }

    @Test
    void showsTheAdminButtonMenuOnlyToTheConfiguredAdmin() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);

        bot().consume(update(chatId, adminId, "/wow_admin"));

        SendMessage message = sentMessage();
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText()))
                .contains("Users", "User Access", "Profiles", "Profile Access", "Manage Alts",
                        "M+ Status", "Vault Reminder", "Housing Sales")
                .doesNotContain("Mythic+", "Tokens");
    }

    @Test
    void letsTheAdminDisableAnAltWithoutChangingTheMain() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);
        when(trackedPlayerService.profiles()).thenReturn(List.of(new PlayerProfile(
                7L,
                "Linq",
                456L,
                true,
                List.of(
                        new ProfileCharacter(21L, "eu", "stormscale", "Linq", true, true),
                        new ProfileCharacter(22L, "eu", "stormscale", "Thelinqq", false, true)
                )
        )));

        bot().consume(callbackUpdate(chatId, adminId, "admin:alt:7:22:false"));

        verify(trackedPlayerService).setCharacterActive(7L, 22L, false);
        verify(trackedPlayerService, never()).switchCharacter(any(), any(), any(), any());
        assertThat(sentMessage().getText()).contains("Thelinqq disabled and hidden from the owner’s alt list.");
    }

    @Test
    void routesAdminUserAccessCallbacks() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);

        bot().consume(callbackUpdate(chatId, adminId, "admin:user:456:false"));

        verify(telegramBotUserService).setActive(456L, false);
        verify(accessPolicy).userAccessChanged(456L);
        assertThat(sentMessage().getText()).isEqualTo("Telegram user 456 disabled.");
    }

    @Test
    void rejectsUnknownAdminCallbackShapes() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);

        bot().consume(callbackUpdate(chatId, adminId, "admin:unknown:value"));

        assertThat(sentMessage().getText())
                .isEqualTo("That admin selection is no longer valid. Use /wow_admin to start again.");
    }

    @Test
    void letsTheAdminAddAnAltThroughTheButtonPrompt() throws Exception {
        long chatId = 123L;
        long adminId = 999L;
        when(accessPolicy.isAllowed(chatId, adminId)).thenReturn(true);
        when(trackedPlayerService.profiles()).thenReturn(List.of(new PlayerProfile(
                7L,
                "Linq",
                456L,
                true,
                List.of(new ProfileCharacter(21L, "eu", "stormscale", "Linq", true, true))
        )));
        BlackBoxBot subject = bot();

        subject.consume(callbackUpdate(chatId, adminId, "admin:alt_add:7"));
        subject.consume(update(chatId, adminId, "draenor Linqalt"));

        verify(trackedPlayerService).addCharacter(7L, "draenor", "Linqalt");
        ArgumentCaptor<SendMessage> messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, times(2)).execute(messages.capture());
        assertThat(messages.getAllValues().getFirst().getText()).contains("Enter the EU realm and character name");
        assertThat(messages.getAllValues().getLast().getText()).contains("Linqalt-draenor added to profile Linq");
    }

    @Test
    void rejectsTheAdminButtonMenuForARegularUser() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);

        bot().consume(update(chatId, userId, "/wow_admin"));

        assertThat(sentMessage().getText()).contains("only be used by the configured bot administrator");
    }

    @Test
    void showsActiveDatabaseProfilesAfterChoosingAnMPlusReport() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered"),
                new TrackedPlayer(12L, "Lazo", "eu", "draenor", "Lazochar")
        ));

        bot().consume(callbackUpdate(chatId, userId, "mplus:action:progress"));

        SendMessage message = sentMessage();
        assertThat(message.getText()).isEqualTo("Choose a profile for Progress:");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getCallbackData()))
                .contains(
                        "mplus:profile:progress:all",
                        "mplus:profile:progress:11",
                        "mplus:profile:progress:12",
                        "mplus:menu"
                );
    }

    @Test
    void offersAnAllProfilesOptionForCombat() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered")
        ));

        bot().consume(callbackUpdate(chatId, userId, "mplus:action:combat"));

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getCallbackData()))
                .contains("mplus:profile:combat:all", "mplus:profile:combat:11");
    }

    @Test
    void offersAnAllProfilesOptionForTeamReports() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered")
        ));

        bot().consume(callbackUpdate(chatId, userId, "mplus:action:team"));

        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) sentMessage().getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getCallbackData()))
                .contains("mplus:profile:team:all", "mplus:profile:team:11");
    }

    @Test
    void runsTheCombatReportForAllProfilesFromTheButton() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(warcraftLogsStatisticsService.combatMessage("")).thenReturn("All combat profiles");

        bot().consume(callbackUpdate(chatId, userId, "mplus:profile:combat:all"));

        verify(warcraftLogsStatisticsService).combatMessage("");
        assertThat(sentMessage().getText()).isEqualTo("All combat profiles");
    }

    @Test
    void runsTheSelectedMPlusReportForTheChosenActiveProfile() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayer(11L, "Buco", "eu", "stormscale", "Bucothered")
        ));
        when(mplusProgressService.progressMessage("Buco", userId)).thenReturn("Buco progress");

        bot().consume(callbackUpdate(chatId, userId, "mplus:profile:progress:11"));

        verify(mplusProgressService).progressMessage("Buco", userId);
        assertThat(sentMessage().getText()).isEqualTo("Buco progress");
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
                wowTokenReportService,
                itemService,
                mountService,
                timeToGoCommandService,
                trackedPlayerService,
                vaultReminderService,
                raceToWorldFirstService,
                raidReportService,
                mplusDataCollectionService,
                mplusProgressService,
                mplusDungeonVaultService,
                mplusPerformanceService,
                mplusTeamService,
                mplusAdvancedService,
                mplusRunCorrelationService,
                mplusSeasonReportService,
                mplusTitleWatchService,
                warcraftLogsStatisticsService,
                accessPolicy,
                telegramBotUserService,
                telegramDailyPromptService,
                housingSalesReportService
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

    private static Update callbackUpdate(long chatId, long userId, String data) {
        return callbackUpdate(chatId, userId, data, null);
    }

    private static Update callbackUpdate(long chatId, long userId, String data, Integer messageId) {
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        MaybeInaccessibleMessage message = mock(MaybeInaccessibleMessage.class);
        User user = mock(User.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callback);
        when(callback.getId()).thenReturn("callback-id");
        when(callback.getData()).thenReturn(data);
        when(callback.getMessage()).thenReturn(message);
        when(callback.getFrom()).thenReturn(user);
        when(message.getChatId()).thenReturn(chatId);
        if (messageId != null) {
            when(message.getMessageId()).thenReturn(messageId);
        }
        when(user.getId()).thenReturn(userId);
        return update;
    }
}
