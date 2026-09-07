package com.blackbox.wow.blizzard;

import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlizzardEquipmentServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ITEMS = "equipped_items";
    private static final String ENCHANTS = "enchantments";
    private static final String SOCKETS = "sockets";
    private static final String MAIN_HAND = "MAIN_HAND";
    private static final String OFF_HAND = "OFF_HAND";
    private static final String TYPE = "type";
    private static final String ITEM = "item";
    private static final String ID = "id";
    private static final String PERMANENT = "PERMANENT";
    private static final String EQUIPMENT_PATH = "/profile/wow/character/{realm}/{name}/equipment";
    private static final Map<String, String> QUERY = Map.of("namespace", "profile-eu", "locale", "en_GB");
    private static final Instant UPDATED = Instant.parse("2026-09-07T10:00:00Z");
    private static final TrackedPlayer PLAYER = new TrackedPlayer(1, "Player", "eu", "Stormscale", "Máge");

    private final BlizzardApiClient api = mock(BlizzardApiClient.class);
    private final BlizzardEquipmentService service = new BlizzardEquipmentService(api);

    @Test
    void acceptsCompleteMidnightGearAndRetainsSourceTimestamp() {
        ObjectNode data = completeGear();
        addItem(data, "BACK", 4).remove(ENCHANTS);
        addItem(data, "WRIST", 4).remove(ENCHANTS);
        addSocket(item(data, "HEAD"), true);
        addSocket(item(data, "FINGER_1"), true);
        stub(data);

        var result = service.check(PLAYER);

        assertThat(result.complete()).isTrue();
        assertThat(result.enchantSlots()).isEqualTo(8);
        assertThat(result.filledSockets()).isEqualTo(2);
        assertThat(result.sourceUpdatedAt()).isEqualTo(UPDATED);
    }

    @Test
    void reportsMissingEnchantsAndCountsOnlyGemsWithItemIds() {
        ObjectNode data = completeGear();
        item(data, "HEAD").remove(ENCHANTS);
        item(data, "LEGS").remove(ENCHANTS);
        addSocket(item(data, "FINGER_1"), true);
        addSocket(item(data, "FINGER_1"), false);
        addSocket(item(data, "FINGER_2"), false).putObject(ITEM).put(ID, 0);
        stub(data);

        var result = service.check(PLAYER);

        assertThat(result.complete()).isFalse();
        assertThat(result.missingEnchants()).containsExactly("Head", "Legs");
        assertThat(result.sockets()).isEqualTo(3);
        assertThat(result.filledSockets()).isEqualTo(1);
        assertThat(result.emptySockets()).containsExactly("Ring 1 (1)", "Ring 2 (1)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEMPORARY", "ILLUSION", "BONUS", ""})
    void doesNotMistakeOtherEnhancementsForPermanentEnchants(String type) {
        ObjectNode data = completeGear();
        ObjectNode weapon = item(data, MAIN_HAND);
        weapon.remove(ENCHANTS);
        addEnchant(weapon, type);
        stub(data);

        assertThat(service.check(PLAYER).missingEnchants()).containsExactly("Main hand");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void requiresOffHandEnchantsOnlyForWeapons(int itemClass) {
        ObjectNode data = completeGear();
        addItem(data, OFF_HAND, itemClass).remove(ENCHANTS);
        stub(data);

        var result = service.check(PLAYER);

        assertThat(result.enchantSlots()).isEqualTo(itemClass == 2 ? 9 : 8);
        assertThat(result.missingEnchants()).isEqualTo(itemClass == 2 ? List.of("Off hand") : List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"equipped_items\":[]}", "{\"equipped_items\":[{}]}"})
    void rejectsUnavailableOrMalformedEquipment(String body) throws Exception {
        stub((ObjectNode) JSON.readTree(body));

        assertThatThrownBy(() -> service.check(PLAYER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPartialEquipmentInsteadOfReportingSuccess() {
        ObjectNode data = completeGear();
        data.withArray(ITEMS).remove(0);
        stub(data);

        assertThatThrownBy(() -> service.check(PLAYER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedSockets() {
        ObjectNode data = completeGear();
        item(data, "HEAD").putObject(SOCKETS);
        stub(data);

        assertThatThrownBy(() -> service.check(PLAYER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retriesMalformedEquipmentBeforeSharingItWithOtherReports() {
        ObjectNode invalid = completeGear();
        item(invalid, "HEAD").putObject(SOCKETS);
        when(api.profileQuery()).thenReturn(QUERY);
        when(api.getSnapshot(anyString(), anyMap(), anyMap()))
                .thenReturn(new BlizzardApiClient.ApiSnapshot(invalid, UPDATED, UPDATED))
                .thenReturn(new BlizzardApiClient.ApiSnapshot(completeGear(), UPDATED, UPDATED));

        assertThatThrownBy(() -> service.snapshot(PLAYER)).isInstanceOf(IllegalStateException.class);
        assertThat(service.check(PLAYER).complete()).isTrue();
        assertThat(service.snapshot(PLAYER).items()).isNotEmpty();
        verify(api, times(2)).getSnapshot(anyString(), anyMap(), anyMap());
    }

    @Test
    void cachesByCharacterAndPreservesAccentsInRequestVariables() {
        stub(completeGear());
        service.check(PLAYER);
        service.check(PLAYER);
        service.check(new TrackedPlayer(1, "Player", "eu", "Stormscale", "Alt"));

        verify(api).getSnapshot(EQUIPMENT_PATH, Map.of("realm", "stormscale", "name", "máge"), QUERY);
        verify(api).getSnapshot(EQUIPMENT_PATH, Map.of("realm", "stormscale", "name", "alt"), QUERY);
        verify(api, times(2)).getSnapshot(anyString(), anyMap(), anyMap());
    }

    @Test
    void rejectsDifferentRegionsBeforeFetchingEquipment() {
        when(api.profileQuery()).thenReturn(QUERY);

        assertThatThrownBy(() -> service.check(new TrackedPlayer(1, "Player", "us", "Stormrage", "Mage")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(api, times(0)).getSnapshot(anyString(), anyMap(), anyMap());
    }

    @Test
    void doesNotCacheFailedRequests() {
        when(api.profileQuery()).thenReturn(QUERY);
        when(api.getSnapshot(anyString(), anyMap(), anyMap()))
                .thenThrow(new IllegalStateException("Unavailable"))
                .thenReturn(new BlizzardApiClient.ApiSnapshot(completeGear(), UPDATED, UPDATED));

        assertThatThrownBy(() -> service.check(PLAYER)).isInstanceOf(IllegalStateException.class);
        assertThat(service.check(PLAYER).complete()).isTrue();
    }

    private void stub(ObjectNode data) {
        when(api.profileQuery()).thenReturn(QUERY);
        when(api.getSnapshot(anyString(), anyMap(), anyMap()))
                .thenReturn(new BlizzardApiClient.ApiSnapshot(data, UPDATED, UPDATED));
    }

    private static ObjectNode completeGear() {
        ObjectNode data = JSON.createObjectNode();
        for (String slot : List.of("HEAD", "SHOULDER", "CHEST", "LEGS", "FEET", "FINGER_1", "FINGER_2")) {
            addItem(data, slot, 4);
        }
        addItem(data, MAIN_HAND, 2);
        return data;
    }

    private static ObjectNode addItem(ObjectNode data, String slot, int itemClass) {
        ObjectNode item = data.withArray(ITEMS).addObject();
        item.putObject("slot").put(TYPE, slot);
        item.putObject(ITEM).put(ID, 123);
        item.putObject("item_class").put(ID, itemClass);
        addEnchant(item, PERMANENT);
        return item;
    }

    private static void addEnchant(ObjectNode item, String type) {
        ObjectNode enchant = item.withArray(ENCHANTS).addObject();
        enchant.put("enchantment_id", 1234);
        enchant.putObject("enchantment_slot").put(ID, 0).put(TYPE, type);
    }

    private static ObjectNode addSocket(ObjectNode item, boolean filled) {
        ObjectNode socket = item.withArray(SOCKETS).addObject();
        socket.putObject("socket_type").put(TYPE, "PRISMATIC");
        if (filled) {
            socket.putObject(ITEM).put(ID, 456);
        }
        return socket;
    }

    private static ObjectNode item(ObjectNode data, String slot) {
        for (var item : data.path(ITEMS)) {
            if (slot.equals(item.path("slot").path(TYPE).asText())) {
                return (ObjectNode) item;
            }
        }
        throw new IllegalArgumentException(slot);
    }
}
