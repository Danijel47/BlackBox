package com.blackbox.wow.blizzard;

import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlizzardItemLevelServiceTest {
    private static final Map<String, String> QUERY = Map.of("namespace", "profile-eu", "locale", "en_GB");
    private static final TrackedPlayer PLAYER = new TrackedPlayer(1, "Player", "EU", "Tarren Mill", "Máge");
    private final BlizzardApiClient api = mock(BlizzardApiClient.class);
    private final BlizzardItemLevelService service = new BlizzardItemLevelService(api);

    @Test
    void fetchesEquippedLevelInsteadOfBagAverageAndRefreshesOnNextRequest() throws Exception {
        when(api.profileQuery()).thenReturn(QUERY);
        var json = JsonMapper.builder().build();
        when(api.get("/profile/wow/character/{realm}/{name}",
                Map.of("realm", "tarren-mill", "name", "máge"), QUERY))
                .thenReturn(json.readTree("{\"equipped_item_level\":303,\"average_item_level\":310}"))
                .thenReturn(json.readTree("{\"equipped_item_level\":306,\"average_item_level\":310}"));

        assertThat(service.equippedItemLevel(PLAYER)).isEqualByComparingTo("303");
        assertThat(service.equippedItemLevel(PLAYER)).isEqualByComparingTo("306");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"average_item_level\":310}",
            "{\"equipped_item_level\":null}", "{\"equipped_item_level\":\"303\"}",
            "{\"equipped_item_level\":0}", "{\"equipped_item_level\":-1}"})
    void treatsMissingOrInvalidEquippedLevelAsUnavailable(String body) throws Exception {
        when(api.profileQuery()).thenReturn(QUERY);
        when(api.get(anyString(), anyMap(), anyMap())).thenReturn(JsonMapper.builder().build().readTree(body));

        assertThat(service.equippedItemLevel(PLAYER)).isNull();
    }

    @Test
    void rejectsUnsupportedRegionBeforeRequestingCharacter() {
        when(api.profileQuery()).thenReturn(QUERY);

        assertThatThrownBy(() -> service.equippedItemLevel(
                new TrackedPlayer(1, "Player", "us", "Stormrage", "Mage")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).get(anyString(), anyMap(), anyMap());
    }
}
