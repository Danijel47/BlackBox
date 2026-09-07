package com.blackbox.wow.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GearUpgradeRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({
            "12817,266,Adventurer,269", "12825,279,Veteran,282", "12833,292,Champion,295",
            "12841,305,Hero,308", "12849,318,Myth,321"
    })
    void resolvesEveryRankOfEachSeasonTwoTrack(int firstBonus, int firstLevel, String name, int nextLevel) {
        int[] offsets = {0, 3, 6, 10, 13, 16};
        for (int index = 0; index < offsets.length; index++) {
            var result = GearUpgradeRules.resolve(JSON.createArrayNode().add(firstBonus + index), firstLevel + offsets[index]);

            assertThat(result).isPresent();
            var step = result.orElseThrow();
            assertThat(step.track().name()).isEqualTo(name);
            assertThat(step.rank()).isEqualTo(index + 1);
            assertThat(step.maxed()).isEqualTo(index == offsets.length - 1);
            assertThat(step.nextLevel()).isEqualTo(firstLevel + offsets[Math.min(index + 1, offsets.length - 1)]);
        }
        assertThat(GearUpgradeRules.resolve(JSON.createArrayNode().add(firstBonus), firstLevel)
                .orElseThrow().nextLevel()).isEqualTo(nextLevel);
    }

    @Test
    void distinguishesOverlappingTracksWithoutGuessingFromItemLevel() {
        var champion = GearUpgradeRules.resolve(JSON.createArrayNode().add(12837), 305).orElseThrow();
        var hero = GearUpgradeRules.resolve(JSON.createArrayNode().add(12841), 305).orElseThrow();

        assertThat(champion.label()).isEqualTo("Champion 5/6");
        assertThat(hero.label()).isEqualTo("Hero 1/6");
        assertThat(champion.crest()).isNotEqualTo(hero.crest());
        assertThat(GearUpgradeRules.resolve(JSON.createArrayNode(), 305)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[\"12841\"]", "[12841.5]", "[2147483648]", "[999999]", "[12841,12837]", "[12842]"})
    void rejectsMalformedConflictingLegacyAndMismatchedBonuses(String body) throws Exception {
        assertThat(GearUpgradeRules.resolve(JSON.readTree(body), 305)).isEmpty();
    }

    @Test
    void ignoresUnrelatedBonusesAndAcceptsRepeatedIdenticalBonus() {
        assertThat(GearUpgradeRules.resolve(JSON.createArrayNode().add(1).add(12841).add(12841), 305))
                .isPresent();
    }
}
