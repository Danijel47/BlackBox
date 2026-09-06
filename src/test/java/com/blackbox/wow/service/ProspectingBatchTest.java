package com.blackbox.wow.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProspectingBatchTest {

    @ParameterizedTest
    @EnumSource(ProspectingOre.class)
    void everyPickerOreResolvesToTheSameQualitySpecificInput(ProspectingOre ore) {
        assertThat(ProspectingBatch.parse(ore.alias() + " 1000 100:20").oreId()).isEqualTo(ore.itemId());
        assertThat(ProspectingOre.fromItemId(ore.itemId())).contains(ore);
        assertThat(ProspectingOre.fromAlias(ore.alias())).contains(ore);
    }

    @Test
    void acceptsQualitySpecificOreAliasesAndCollectsAllItemIds() {
        var batch = ProspectingBatch.parse("  COPPER2  1000\n100:20 101:30 ");

        assertThat(batch.oreId()).isEqualTo(237361L);
        assertThat(batch.oreQuantity()).isEqualTo(1000L);
        assertThat(batch.outputs()).containsExactlyInAnyOrderEntriesOf(Map.of(100L, 20L, 101L, 30L));
        assertThat(batch.itemIds()).containsExactlyInAnyOrder(237361L, 100L, 101L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "copper1 1000", "copper1 0 100:2", "copper1 1000001 100:2",
            "copper1 100 100:2 100:3", "copper1 100 237359:3", "copper1 100 100:-1",
            "copper1 100 100:2:3", "copper1 100 100:", "copper1 100 100:2.5",
            "unknown 100 100:3", "-1 100 100:3", "copper1 100 100:9223372036854775808"
    })
    void rejectsInvalidOrAmbiguousSamples(String arguments) {
        assertThatThrownBy(() -> ProspectingBatch.parse(arguments)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsTheNumberOfPriceLookupsBeforeCallingAnExternalService() {
        StringBuilder arguments = new StringBuilder("copper1 1000");
        for (int itemId = 1; itemId <= 21; itemId++) {
            arguments.append(' ').append(itemId).append(":1");
        }

        assertThatThrownBy(() -> ProspectingBatch.parse(arguments.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
