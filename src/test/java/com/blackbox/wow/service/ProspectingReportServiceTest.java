package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.CommodityMarket;
import com.blackbox.wow.blizzard.CommodityMarket.Offer;
import com.blackbox.wow.properties.BlizzardApiProperties;
import com.blackbox.wow.repository.ProspectingSampleRepository;
import com.blackbox.wow.repository.ProspectingSampleRepository.SavedSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProspectingReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:30:00Z");
    private static final String SAMPLE = "copper1 1000 100:200";
    private static final String EU_NAMESPACE = "dynamic-eu";
    private static final long USER_ID = 999L;
    @Mock private BlizzardAuctionService auctions;
    @Mock private BlizzardItemService items;
    @Mock private ProspectingSampleRepository samples;

    @Test
    void includesMarketDepthAuctionFeesBreakEvenAndPriceStress() {
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        String report = service(EU_NAMESPACE).report(SAMPLE);

        assertThat(report).contains(
                "Stormscale EU (EU-wide commodities)",
                "Positive estimated margin",
                "Ore purchase cost (full batch): 19000.00g",
                "After 5% AH cut: 22800.00g",
                "Estimated margin: 3800.00g (ROI 20.0%)",
                "Break-even average ore price: 22.80g",
                "Margin if output prices fall 10%: 1520.00g",
                "Blizzard snapshot: 2026-09-06 10:30 UTC",
                "all outputs sell", "Deposits lost", "not live quotes"
        );
        verify(auctions).getCommodityMarket(Set.of(237359L, 100L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2026-09-06T07:00:00Z", "2026-09-06T11:00:00Z"})
    void avoidsACurrentVerdictForStaleMissingOrFutureSourceTimestamps(String sourceTimestamp) {
        Instant updated = sourceTimestamp == null ? null : Instant.parse(sourceTimestamp);
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(updated));

        String report = service(EU_NAMESPACE).report(SAMPLE);

        assertThat(report).contains("no current profitability verdict")
                .doesNotContain("Positive estimated margin");
    }

    @Test
    void refusesToValueABatchWithInsufficientOreSupply() {
        when(auctions.getCommodityMarket(anySet())).thenReturn(new CommodityMarket(
                Map.of(237359L, List.of(new Offer(1, 999))), NOW, NOW));

        assertThat(service(EU_NAMESPACE).report(SAMPLE)).contains("Not enough listed ore", "No profitability estimate");
    }

    @Test
    void refusesToSilentlyValueMissingGemPricesAtZero() {
        when(auctions.getCommodityMarket(anySet())).thenReturn(new CommodityMarket(
                Map.of(237359L, List.of(new Offer(1, 1000))), NOW, NOW));

        assertThat(service(EU_NAMESPACE).report(SAMPLE))
                .contains("Missing commodity prices for output IDs: [100]", "No profitability estimate");
    }

    @Test
    void displaysLossesAndWarnsAboutSmallSamples() {
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        assertThat(service(EU_NAMESPACE).report("copper1 100 100:1"))
                .contains("Negative estimated margin", "Estimated margin: -886.00g", "Small sample");
    }

    @Test
    void explainsHowToSupplyObservedYieldsWithoutFetchingPrices() {
        assertThat(service(EU_NAMESPACE).report(""))
                .contains(ProspectingReportService.COMMAND, "ALL saleable outputs", "ProspectMate");
        assertThat(service(EU_NAMESPACE).report("copper1 -1 100:1")).contains("positive ore ID");

        verifyNoInteractions(auctions, items);
    }

    @Test
    void doesNotMislabelPricesFromAnotherRegionAsStormscale() {
        assertThat(service("dynamic-us").report(SAMPLE)).contains("requires the EU retail commodity market");

        verifyNoInteractions(auctions, items);
    }

    @Test
    void keepsExternalFailureDetailsOutOfTelegram() {
        when(auctions.getCommodityMarket(anySet())).thenThrow(new IllegalStateException("private-source-url"));

        assertThat(service(EU_NAMESPACE).report(SAMPLE))
                .contains("temporarily unavailable").doesNotContain("private-source-url");
    }

    @Test
    void savesKnownOreSamplesForTheRequestingUserAndQuality() {
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        String report = service(EU_NAMESPACE).saveAndReport(USER_ID, SAMPLE);

        verify(samples).save(USER_ID, ProspectingBatch.parse(SAMPLE), NOW);
        assertThat(report).contains("Estimated margin: 3800.00g", "Recorded batch saved", "All ores");
    }

    @Test
    void repricesOnlyTheSelectedOwnersSavedOre() {
        when(samples.find(USER_ID, ProspectingOre.COPPER_ONE.itemId()))
                .thenReturn(Optional.of(sample(SAMPLE)));
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        Optional<String> report = service(EU_NAMESPACE).savedReport(USER_ID, ProspectingOre.COPPER_ONE);

        assertThat(report).hasValueSatisfying(value ->
                assertThat(value).contains("Estimated margin: 3800.00g", "Sample saved: 2026-09-06 10:30 UTC"));
        verify(samples).find(USER_ID, ProspectingOre.COPPER_ONE.itemId());
    }

    @Test
    void missingSavedSampleDoesNotFetchPrices() {
        assertThat(service(EU_NAMESPACE).savedReport(USER_ID, ProspectingOre.COPPER_TWO)).isEmpty();

        verifyNoInteractions(auctions, items);
    }

    @Test
    void invalidBatchesAreNotSavedOrPriced() {
        assertThat(service(EU_NAMESPACE).saveAndReport(USER_ID, "copper1 -1 100:1"))
                .contains("positive ore ID");

        verifyNoInteractions(samples, auctions, items);
    }

    @Test
    void numericLegacyOreStillWorksWithoutJoiningTheSavedComparison() {
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        assertThat(service(EU_NAMESPACE).saveAndReport(USER_ID, "12345 1000 100:200"))
                .contains("Not enough listed ore");

        verifyNoInteractions(samples);
    }

    @Test
    void storageFailureDoesNotExposeDetailsOrClaimTheSampleWasSaved() {
        org.mockito.Mockito.doThrow(new IllegalStateException("private-database-url"))
                .when(samples).save(USER_ID, ProspectingBatch.parse(SAMPLE), NOW);

        assertThat(service(EU_NAMESPACE).saveAndReport(USER_ID, SAMPLE))
                .contains("Could not save").doesNotContain("private-database-url", "Recorded batch saved");
        verifyNoInteractions(auctions, items);
    }

    @Test
    void comparesDifferentYieldsOnTheSameOreQuantityUsingOneSnapshotAndMarketDepth() {
        when(samples.findAll(USER_ID)).thenReturn(List.of(
                sample(SAMPLE), sample("tin1 100 101:50"), sample("copper2 2000 100:200")));
        when(auctions.getCommodityMarket(anySet())).thenReturn(comparisonMarket(NOW));

        String report = service(EU_NAMESPACE).compareSaved(USER_ID);

        assertThat(report).contains(
                "Highest estimate among priced samples: Umbral Tin Ore — Q1",
                "Umbral Tin Ore — Q1\n   46500.00g per 1,000 ore | ROI 4650.0%",
                "Refulgent Copper Ore — Q1\n   3800.00g per 1,000 ore | ROI 20.0%",
                "Refulgent Copper Ore — Q2\n   1400.00g per 1,000 ore | ROI 14.0%",
                "Cost 19000.00g | break-even/ore 22.80g",
                "Sample: 100 ore", "Sample: 2000 ore", "No sample yet", "5% AH cut", "Small samples"
        );
        assertThat(report.indexOf("46500.00g")).isLessThan(report.indexOf("3800.00g"));
        assertThat(report.indexOf("3800.00g")).isLessThan(report.indexOf("1400.00g"));
        verify(auctions).getCommodityMarket(Set.of(237359L, 237361L, 237362L, 100L, 101L));
        verify(samples).findAll(USER_ID);
        verifyNoInteractions(items);
    }

    @Test
    void allOresExplainsMissingSamplesWithoutInventingYields() {
        assertThat(service(EU_NAMESPACE).compareSaved(USER_ID))
                .contains("No recorded batches yet", "Each ore and quality needs its own sample");

        verifyNoInteractions(auctions, items);
    }

    @Test
    void sevenMaximumOutputSamplesFitOneTelegramMessageAndUseOneMarketLookup() {
        List<SavedSample> recorded = new ArrayList<>();
        Map<Long, List<Offer>> offers = new HashMap<>();
        for (ProspectingOre ore : ProspectingOre.values()) {
            Map<Long, Long> outputs = new HashMap<>();
            for (int index = 0; index < 20; index++) {
                long outputId = 10_000L + ore.ordinal() * 100L + index;
                outputs.put(outputId, 1_000_000L);
                offers.put(outputId, List.of(new Offer(Long.MAX_VALUE, 1_000_000L)));
            }
            recorded.add(new SavedSample(new ProspectingBatch(ore.itemId(), 1L, outputs), NOW));
            offers.put(ore.itemId(), List.of(new Offer(Long.MAX_VALUE, 1000L)));
        }
        when(samples.findAll(USER_ID)).thenReturn(recorded);
        when(auctions.getCommodityMarket(anySet())).thenReturn(new CommodityMarket(offers, NOW, NOW));

        String report = service(EU_NAMESPACE).compareSaved(USER_ID);

        assertThat(report).hasSizeLessThanOrEqualTo(4096).contains("Highest estimate among priced samples");
        for (ProspectingOre ore : ProspectingOre.values()) {
            assertThat(report).contains(ore.label());
        }
        assertThat(offers).hasSize(147);
        verify(auctions).getCommodityMarket(offers.keySet());
    }

    @Test
    void unpriceableSamplesCannotWinTheComparison() {
        when(samples.findAll(USER_ID)).thenReturn(List.of(
                sample(SAMPLE), sample("tin1 100 999:1000"), sample("silver1 100 100:1000")));
        when(auctions.getCommodityMarket(anySet())).thenReturn(comparisonMarket(NOW));

        assertThat(service(EU_NAMESPACE).compareSaved(USER_ID)).contains(
                "Highest estimate among priced samples: Refulgent Copper Ore — Q1",
                "Umbral Tin Ore — Q1\n   Missing output prices.",
                "Brilliant Silver Ore — Q1\n   Not enough listed ore", "Missing samples are not ranked"
        );
    }

    @Test
    void comparisonDoesNotRecommendALosingSample() {
        when(samples.findAll(USER_ID)).thenReturn(List.of(sample("copper1 1000 100:1")));
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        assertThat(service(EU_NAMESPACE).compareSaved(USER_ID))
                .contains("No priced sample currently shows a positive estimated margin", "-18886.00g")
                .doesNotContain("Highest estimate");
    }

    @Test
    void comparisonExplainsWhenNoneOfTheSamplesCanBePriced() {
        when(samples.findAll(USER_ID)).thenReturn(List.of(sample("tin1 100 100:1")));
        when(auctions.getCommodityMarket(anySet())).thenReturn(market(NOW));

        assertThat(service(EU_NAMESPACE).compareSaved(USER_ID))
                .contains("No saved sample could be priced completely").doesNotContain("Highest estimate");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2026-09-06T07:00:00Z", "2026-09-06T11:00:00Z"})
    void staleComparisonDoesNotDeclareAWinner(String timestamp) {
        when(samples.findAll(USER_ID)).thenReturn(List.of(sample(SAMPLE)));
        when(auctions.getCommodityMarket(anySet()))
                .thenReturn(market(timestamp == null ? null : Instant.parse(timestamp)));

        assertThat(service(EU_NAMESPACE).compareSaved(USER_ID))
                .contains("no current profitability verdict", "Retrieved: 2026-09-06 10:30 UTC")
                .doesNotContain("Highest estimate", "3800.00g");
    }

    @Test
    void comparisonRejectsAnotherRegionBeforeReadingSamples() {
        assertThat(service("dynamic-us").compareSaved(USER_ID)).contains("requires the EU retail commodity market");

        verifyNoInteractions(samples, auctions, items);
    }

    @Test
    void nonEuConfigurationCannotSaveOrRepriceSavedSamples() {
        var service = service("dynamic-us");

        assertThat(service.saveAndReport(USER_ID, SAMPLE)).contains("requires the EU retail commodity market");
        assertThat(service.savedReport(USER_ID, ProspectingOre.COPPER_ONE))
                .hasValueSatisfying(value -> assertThat(value).contains("requires the EU retail commodity market"));
        verifyNoInteractions(samples, auctions, items);
    }

    @Test
    void comparisonKeepsStorageFailureDetailsOutOfTelegram() {
        when(samples.findAll(USER_ID)).thenThrow(new IllegalStateException("private-database-url"));

        assertThat(service(EU_NAMESPACE).compareSaved(USER_ID))
                .contains("Could not compare").doesNotContain("private-database-url");
    }

    private ProspectingReportService service(String namespace) {
        var properties = new BlizzardApiProperties(null, null, null, null, namespace, "en_GB", null);
        return new ProspectingReportService(auctions, items, properties, Clock.fixed(NOW, ZoneOffset.UTC), samples);
    }

    private static SavedSample sample(String arguments) {
        return new SavedSample(ProspectingBatch.parse(arguments), NOW);
    }

    private static CommodityMarket comparisonMarket(Instant timestamp) {
        return new CommodityMarket(Map.of(
                237359L, List.of(new Offer(100_000, 100), new Offer(200_000, 900)),
                237361L, List.of(new Offer(100_000, 1000)),
                237362L, List.of(new Offer(10_000, 1000)),
                100L, List.of(new Offer(1_200_000, 500)),
                101L, List.of(new Offer(1_000_000, 500))
        ), timestamp, NOW);
    }

    private static CommodityMarket market(Instant sourceUpdatedAt) {
        return new CommodityMarket(Map.of(
                237359L, List.of(new Offer(100_000, 100), new Offer(200_000, 900)),
                100L, List.of(new Offer(1_200_000, 500))
        ), sourceUpdatedAt, NOW);
    }
}
