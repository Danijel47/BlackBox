package com.blackbox.wow.bot;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardItemService.ItemRef;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.WowTokenReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EconomyCommandHandlerTest {

    private static final long CHAT_ID = 123L;

    @Mock private BlizzardAuctionService auctionService;
    @Mock private WowTokenReportService tokenReportService;
    @Mock private BlizzardItemService itemService;

    @Test
    void numericPriceUsesTheRegionalMarketWhenNoRealmIsSupplied() {
        List<SentMessage> messages = new ArrayList<>();
        when(itemService.getById(72092L)).thenReturn(new ItemRef(72092L, "Ghost Iron Ore"));
        when(auctionService.getRegionAverage(72092L)).thenReturn(price(1_234_567L));

        boolean handled = handler(messages).handle(CHAT_ID, "/price 72092", "/price");

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Region avg (EU) avg for Ghost Iron Ore: 123g 45s"
        ));
    }

    @Test
    void numericPriceUsesTheRequestedRealm() {
        List<SentMessage> messages = new ArrayList<>();
        when(itemService.getById(72092L)).thenReturn(new ItemRef(72092L, "Ghost Iron Ore"));
        when(auctionService.getRealmAverage("Draenor", 72092L)).thenReturn(price(900_099L));

        handler(messages).handle(CHAT_ID, "/price 72092 Draenor", "/price");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Realm Draenor avg for Ghost Iron Ore: 90g 0s"
        ));
    }

    @Test
    void namedWowTokenUsesTheDedicatedTokenEndpoint() {
        List<SentMessage> messages = new ArrayList<>();
        when(itemService.findByName("wow token")).thenReturn(new ItemRef(122284L, "WoW Token"));
        when(auctionService.getWowTokenPrice()).thenReturn(price(3_456_700_00L));

        handler(messages).handle(CHAT_ID, "/price wow token", "/price");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "WoW Token (EU) avg for WoW Token: 34567g 0s"
        ));
        verify(auctionService, never()).getRegionAverage(122284L);
    }

    @Test
    void missingNamedItemReturnsAUsefulMessage() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handle(CHAT_ID, "/price imaginary ore", "/price");

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Item not found: imaginary ore"));
    }

    @Test
    void missingPriceArgumentReturnsUsageWithoutCallingProviders() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handle(CHAT_ID, "/price", "/price");

        assertThat(messages).singleElement().extracting(SentMessage::text)
                .asString().contains("Usage: /price <itemId|item name> [realm-if-itemId]");
        verifyNoInteractions(auctionService, itemService);
    }

    @Test
    void auctionHousePriceValidatesAllNumericArguments() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handle(CHAT_ID, "/priceah realm 2 72092", "/priceah");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Invalid numbers. Example: /price_ah 1080 2 72092"
        ));
        verifyNoInteractions(auctionService, itemService);
    }

    @Test
    void auctionHousePriceUsesAllThreeIdentifiers() {
        List<SentMessage> messages = new ArrayList<>();
        when(auctionService.getAuctionHouseAverage(1080L, 2L, 72092L)).thenReturn(price(12_345L));
        when(itemService.getById(72092L)).thenReturn(new ItemRef(72092L, "Ghost Iron Ore"));

        handler(messages).handle(CHAT_ID, "/priceah 1080 2 72092", "/priceah");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "AuctionHouse 2 avg for Ghost Iron Ore: 1g 23s"
        ));
    }

    @Test
    void priceProviderFailureIsConvertedToTheExistingUserMessage() {
        List<SentMessage> messages = new ArrayList<>();
        when(itemService.findByName("Ghost Iron Ore")).thenThrow(new IllegalStateException("API unavailable"));

        handler(messages).handle(CHAT_ID, "/price Ghost Iron Ore", "/price");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Blizzard price lookup failed: API unavailable"
        ));
    }

    @Test
    void currentTokenCommandSendsTheCurrentReport() {
        List<SentMessage> messages = new ArrayList<>();
        when(tokenReportService.currentPrice()).thenReturn("Current token price");

        handler(messages).handle(CHAT_ID, "/token", "/token");

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Current token price"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/token_lowest_week", "/tokenlowest week"})
    void lowestWeekAliasesUseASevenDayLookback(String text) {
        List<SentMessage> messages = new ArrayList<>();
        String command = text.split(" ")[0];
        when(tokenReportService.lowestPrice(Duration.ofDays(7), "last week")).thenReturn("Lowest price");

        handler(messages).handle(CHAT_ID, text, command);

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Lowest price"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/token_highest_month", "/tokenhighest month"})
    void highestMonthAliasesUseAThirtyDayLookback(String text) {
        List<SentMessage> messages = new ArrayList<>();
        String command = text.split(" ")[0];
        when(tokenReportService.highestPrice(Duration.ofDays(30), "last 30 days"))
                .thenReturn("Highest price");

        handler(messages).handle(CHAT_ID, text, command);

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Highest price"));
    }

    @Test
    void fixedPeriodTokenCommandRejectsExtraArguments() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handle(CHAT_ID, "/token_lowest_week month", "/token_lowest_week");

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Usage: /token_lowest_week"));
        verifyNoInteractions(tokenReportService);
    }

    @Test
    void compatibilityTokenCommandRejectsUnsupportedPeriod() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handle(CHAT_ID, "/tokenhighest year", "/tokenhighest");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Usage: /token_highest_week or /token_highest_month"
        ));
        verifyNoInteractions(tokenReportService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/token_best", "/tokenbest"})
    void bestTokenAliasesSendTheRecurringHoursReport(String command) {
        List<SentMessage> messages = new ArrayList<>();
        when(tokenReportService.bestTradingHours()).thenReturn("Best trading hours");

        handler(messages).handle(CHAT_ID, command, command);

        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Best trading hours"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ores", "/ore"})
    void oreAliasesFormatConfiguredSilverAndGoldPrices(String command) {
        List<SentMessage> messages = new ArrayList<>();
        when(auctionService.getRegionBuyPrice(237359L)).thenReturn(price(12_345L));
        when(auctionService.getRegionBuyPrice(237361L)).thenReturn(price(67_890L));

        handler(messages).handle(CHAT_ID, command, command);

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Ores (EU)\n• Refulgent Copper Ore | S: 1g 23s | G: 6g 78s"
        ));
    }

    @Test
    void unknownCommandIsLeftForTheNextHandler() {
        List<SentMessage> messages = new ArrayList<>();

        boolean handled = handler(messages).handle(CHAT_ID, "/unknown", "/unknown");

        assertThat(handled).isFalse();
        assertThat(messages).isEmpty();
        verifyNoInteractions(auctionService, tokenReportService, itemService);
    }

    private EconomyCommandHandler handler(List<SentMessage> messages) {
        return new EconomyCommandHandler(
                auctionService,
                tokenReportService,
                itemService,
                new WowWatchlistProperties(
                        List.of("Refulgent Copper Ore"),
                        List.of("Tranquility Bloom")
                ),
                (chatId, text) -> messages.add(new SentMessage(chatId, text))
        );
    }

    private static PriceResult price(long copper) {
        return new PriceResult(true, copper, copper / 10_000, copper % 10_000 / 100, copper % 100);
    }

    private record SentMessage(long chatId, String text) {
    }
}
