package com.blackbox.wow.client;

import com.blackbox.wow.properties.HousingMarketProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SaddlebagExchangeClientTest {

    private static final String BASE_URL = "https://api.saddlebagexchange.com";
    private static final String STATS_URL = BASE_URL + "/api/wow/v2/tsmstats";
    private static final Instant FETCHED_AT = Instant.parse("2026-09-05T08:30:00Z");

    @Test
    void requestsAndCachesEuRetailTsmStatsForTheRequestedItems() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(STATS_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.USER_AGENT,
                        "BlackBox-WoW-Market (+https://github.com/Danijel47/TelegramBot)"))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {"item_ids":[264710,264711],"pets":false,"game_edition":"retail"}
                        """))
                .andRespond(withSuccess("""
                        {"data":[
                          {"itemID":264710,"itemName":"Dalaran Sun Sconce",
                           "eu_market_value":14251,"eu_historical":14590,"eu_average_price":4750,
                           "eu_sale_rate":0.02,"eu_sold_per_day":0.08},
                          {"itemID":264711,"itemName":"Books, Large Stack",
                           "eu_market_value":5000,"eu_historical":4900,"eu_average_price":4200,
                           "eu_sale_rate":0.125,"eu_sold_per_day":1.75}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        SaddlebagExchangeClient client = client(builder);

        var firstResult = client.getEuRetailItems(Set.of(264711L, 264710L));
        var cachedResult = client.getEuRetailItems(Set.of(264710L, 264711L));

        assertThat(firstResult).containsExactly(
                new SaddlebagExchangeClient.RegionItem(
                        264710L,
                        "Dalaran Sun Sconce",
                        142_510_000L,
                        145_900_000L,
                        47_500_000L,
                        0.02D,
                        0.08D,
                        FETCHED_AT
                ),
                new SaddlebagExchangeClient.RegionItem(
                        264711L,
                        "Books, Large Stack",
                        50_000_000L,
                        49_000_000L,
                        42_000_000L,
                        0.125D,
                        1.75D,
                        FETCHED_AT
                )
        );
        assertThat(cachedResult).isSameAs(firstResult);
        server.verify();
    }

    @Test
    void rejectsUnexpectedItemIds() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(STATS_URL))
                .andRespond(withSuccess(validItemJson(999L, 0.02D), MediaType.APPLICATION_JSON));
        SaddlebagExchangeClient client = client(builder);

        assertThatThrownBy(() -> client.getEuRetailItems(Set.of(264710L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected item ID");
        server.verify();
    }

    @Test
    void skipsAnIncompleteItemWithoutDiscardingUsableMarketData() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(STATS_URL))
                .andRespond(withSuccess("""
                        {"data":[
                          {"itemID":264710,"itemName":"Incomplete Sconce",
                           "eu_market_value":null,"eu_historical":14590,"eu_average_price":4750,
                           "eu_sale_rate":0.02,"eu_sold_per_day":0.08},
                          {"itemID":264711,"itemName":"Usable Chair",
                           "eu_market_value":5000,"eu_historical":4900,"eu_average_price":4200,
                           "eu_sale_rate":0.125,"eu_sold_per_day":1.75}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        SaddlebagExchangeClient client = client(builder);

        assertThat(client.getEuRetailItems(Set.of(264710L, 264711L)))
                .extracting(SaddlebagExchangeClient.RegionItem::itemId)
                .containsExactly(264711L);
        server.verify();
    }

    @Test
    void rejectsTheResponseWhenEveryReturnedItemIsInvalid() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(STATS_URL))
                .andRespond(withSuccess(validItemJson(264710L, 1.1D), MediaType.APPLICATION_JSON));
        SaddlebagExchangeClient client = client(builder);

        assertThatThrownBy(() -> client.getEuRetailItems(Set.of(264710L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no usable item rows")
                .rootCause()
                .hasMessageContaining("invalid sale rate");
        server.verify();
    }

    @Test
    void doesNotCallTheProviderForAnEmptyCatalog() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SaddlebagExchangeClient client = client(builder);

        assertThat(client.getEuRetailItems(Set.of())).isEmpty();
        server.verify();
    }

    private static SaddlebagExchangeClient client(RestClient.Builder builder) {
        return new SaddlebagExchangeClient(
                builder.build(),
                JsonMapper.builder().build(),
                Clock.fixed(FETCHED_AT, ZoneOffset.UTC),
                new HousingMarketProperties(10, 12, 24)
        );
    }

    private static String validItemJson(long itemId, double saleRate) {
        return """
                {"data":[
                  {"itemID":%d,"itemName":"Dalaran Sun Sconce",
                   "eu_market_value":14251,"eu_historical":14590,"eu_average_price":4750,
                   "eu_sale_rate":%s,"eu_sold_per_day":0.08}
                ]}
                """.formatted(itemId, saleRate);
    }
}
