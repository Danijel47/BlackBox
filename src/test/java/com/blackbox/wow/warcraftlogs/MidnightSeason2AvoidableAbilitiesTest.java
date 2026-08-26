package com.blackbox.wow.warcraftlogs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MidnightSeason2AvoidableAbilitiesTest {

    @Test
    void containsTheCompleteSeasonTwoDungeonCatalogue() {
        assertThat(MidnightSeason2AvoidableAbilities.dungeonCount()).isEqualTo(8);
        assertThat(MidnightSeason2AvoidableAbilities.abilityCount()).isEqualTo(108);
    }

    @Test
    void normalizesDungeonPunctuationAndCase() {
        assertThat(MidnightSeason2AvoidableAbilities.contains("KINGS REST", 268932L)).isTrue();
        assertThat(MidnightSeason2AvoidableAbilities.contains("King’s Rest", 268932L)).isTrue();
    }

    @Test
    void rejectsAnAbilityFromTheWrongDungeon() {
        assertThat(MidnightSeason2AvoidableAbilities.contains("Murder Row", 268932L)).isFalse();
    }
}
