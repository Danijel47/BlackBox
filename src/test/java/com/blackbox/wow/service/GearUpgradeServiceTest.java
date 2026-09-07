package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardApiClient;
import com.blackbox.wow.blizzard.BlizzardEquipmentService;
import com.blackbox.wow.blizzard.BlizzardEquipmentService.EquipmentSnapshot;
import com.blackbox.wow.service.GearUpgradeAdvisor.Priority;
import com.blackbox.wow.service.GearUpgradeAdvisor.Status;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GearUpgradeServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MAIN_HAND = "MAIN_HAND";
    private static final String OFF_HAND = "OFF_HAND";
    private static final String NAME = "name";
    private static final String HEAD = "HEAD";
    private static final String LEVEL = "level";
    private static final String ITEMS = "equipped_items";
    private static final String BONUSES = "bonus_list";
    private static final String CRAFTED = "crafted_by";
    private static final String VALUE = "value";
    private static final String ID = "id";
    private static final String HERO_CRESTS = "20 Hero Mistcrests";
    private static final Instant UPDATED = Instant.parse("2026-09-07T10:00:00Z");
    private static final TrackedPlayer PLAYER = new TrackedPlayer(1, "Alice", "eu", "Stormscale", "Máge");

    private final BlizzardApiClient api = mock(BlizzardApiClient.class);
    private final BlizzardEquipmentService equipment = new BlizzardEquipmentService(api);
    private final GearUpgradeService service = new GearUpgradeService(equipment, api);

    @Test
    void prioritizesWeaponsTrinketsAndLargeArmorBeforeOtherSlots() {
        var advice = GearUpgradeAdvisor.advise(snapshot(gear()), 63);

        assertThat(advice.subList(0, 6)).extracting(GearUpgradeAdvisor.Advice::slot)
                .containsExactly(MAIN_HAND, "TRINKET_1", "TRINKET_2", "CHEST", HEAD, "LEGS");
        assertThat(advice.subList(0, 6)).allMatch(row -> row.priority() == Priority.HIGH);
        assertThat(advice).filteredOn(row -> row.slot().equals("HANDS"))
                .allMatch(row -> row.priority() == Priority.MEDIUM);
        assertThat(advice).filteredOn(row -> row.slot().equals("NECK"))
                .allMatch(row -> row.priority() == Priority.LOW);
    }

    @Test
    void raisesCatchUpPriorityAndPrefersLargerNextStepWithinEquivalentSlots() {
        ObjectNode data = gear();
        setRank(item(data, "HANDS"), 12825, 279);
        setRank(item(data, HEAD), 12843, 311);

        var advice = GearUpgradeAdvisor.advise(snapshot(data), 63);

        assertThat(advice).filteredOn(row -> row.slot().equals("HANDS")).singleElement()
                .satisfies(row -> {
                    assertThat(row.priority()).isEqualTo(Priority.HIGH);
                    assertThat(row.reason()).contains("catch-up priority raised");
                });
        assertThat(advice.subList(3, 6)).extracting(GearUpgradeAdvisor.Advice::slot)
                .containsExactly(HEAD, "CHEST", "LEGS");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 63, 72, 251, 263})
    void adjustsOffHandForSpecializationWithoutTreatingItAsAnotherMainHand(int specializationId) {
        ObjectNode data = gear();
        addItem(data, OFF_HAND);

        var offHand = GearUpgradeAdvisor.advise(snapshot(data), specializationId).stream()
                .filter(row -> row.slot().equals(OFF_HAND)).findFirst().orElseThrow();

        assertThat(offHand.priority()).isEqualTo(specializationId == 72 || specializationId == 251
                ? Priority.HIGH : Priority.MEDIUM);
    }

    @Test
    void neverAssignsWeaponPriorityToAShield() {
        ObjectNode data = gear();
        addItem(data, OFF_HAND);
        item(data, OFF_HAND).withObject("item_class").put(ID, 4);

        assertThat(GearUpgradeAdvisor.advise(snapshot(data), 72))
                .filteredOn(row -> row.slot().equals(OFF_HAND)).singleElement()
                .satisfies(row -> assertThat(row.priority()).isEqualTo(Priority.MEDIUM));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WEAPON", "WEAPONMAINHAND", "TWOHWEAPON", "RANGED", "RANGEDRIGHT"})
    void flagsAMissingOffHandOnlyWhenTheEquippedWeaponRequiresIt(String inventoryType) {
        ObjectNode data = gear();
        item(data, MAIN_HAND).putObject("inventory_type").put("type", inventoryType);

        assertThat(GearUpgradeAdvisor.missingSlots(snapshot(data)))
                .hasSize(inventoryType.equals("WEAPON") || inventoryType.equals("WEAPONMAINHAND") ? 1 : 0);
    }

    @Test
    void excludesCosmeticsAndSeparatesMaxedCraftedAndUnknownItems() {
        ObjectNode data = gear();
        addItem(data, "TABARD");
        setRank(item(data, MAIN_HAND), 12846, 321);
        item(data, HEAD).putObject(CRAFTED).put(ID, 123);
        item(data, "CHEST").remove(BONUSES);
        item(data, "LEGS").withObject(LEVEL).put(VALUE, "305");

        var advice = GearUpgradeAdvisor.advise(snapshot(data), 63);

        assertThat(advice).hasSize(15);
        assertThat(advice).filteredOn(row -> row.slot().equals(MAIN_HAND)).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(Status.MAXED));
        assertThat(advice).filteredOn(row -> row.slot().equals(HEAD)).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(Status.CRAFTED));
        assertThat(advice).filteredOn(row -> row.status() == Status.UNKNOWN).hasSize(2);
        assertThat(advice.getFirst().status()).isEqualTo(Status.UPGRADE);
    }

    @Test
    void detailedReportShowsNextRankReasonsUnknownWalletAndSourceTime() {
        stub(gear());

        String report = String.join("\n", service.details(PLAYER));

        assertThat(report).contains("Gear Upg", "Midnight Season 2", "Fire Mage", "🔴 High", "🟡 Medium", "🟢 Low",
                "Hero 1/6 → 2/6", "ilvl 305 → 308", HERO_CRESTS, "primary-stat", UPDATED.toString(),
                "Crest balance unavailable; affordability unknown", "before same-slot / Warband discounts",
                "Discount eligibility is unknown", GearUpgradeRules.GUIDE_URL);
        assertThat(report).doesNotContain("Partial equipment", "0 crests", "Affordable");
    }

    @Test
    void summaryContainsOnlyThreeNextUpgradesAndOmitsDetailedReasons() {
        stub(gear());

        String report = service.summary(PLAYER);

        assertThat(report).contains("Main hand", "Trinket 1", "Trinket 2", "+12 more in Details", HERO_CRESTS);
        assertThat(report).doesNotContain("CHEST item", "primary-stat", "Hero 1/6");
        assertThat(report.length()).isLessThan(1000);
    }

    @Test
    void marksPartialEquipmentAndSpecializationFailureWithoutLosingTheReport() {
        ObjectNode data = gear();
        data.withArray(ITEMS).remove(3); // Cloak is not needed for the existing enchant check.
        stub(data);
        when(api.get(anyString(), anyMap(), anyMap())).thenThrow(new RestClientException("private upstream URL"));

        assertThat(String.join("\n", service.details(PLAYER)))
                .contains("Partial equipment — missing: Cloak", "general slot priorities used", HERO_CRESTS)
                .doesNotContain("private upstream URL");
        assertThat(service.summary(PLAYER)).contains("Partial equipment");
    }

    @Test
    void sharesEquipmentCacheWithEnchantCheckAndKeepsMainSwitchesFresh() {
        stub(gear());
        equipment.check(PLAYER);
        service.summary(PLAYER);
        service.details(PLAYER);
        service.details(new TrackedPlayer(1, "Alice", "eu", "Stormscale", "Alt"));

        verify(api, times(2)).getSnapshot(anyString(), anyMap(), anyMap());
        verify(api, times(2)).get(anyString(), anyMap(), anyMap());
    }

    @Test
    void boundsLongDetailedPagesAndSanitizesUpstreamNames() {
        ObjectNode data = gear();
        data.path(ITEMS).forEach(row -> ((ObjectNode) row).put(NAME, "Sword\n🔴 Fake priority\u202E" + "🗡".repeat(300)));
        stub(data);

        List<String> pages = service.details(PLAYER);

        assertThat(pages).hasSizeGreaterThan(1).allSatisfy(page -> {
            assertThat(page.length()).isLessThanOrEqualTo(3500);
            assertThat(page).doesNotContain("Sword\n", "\u202E");
        });
        assertThat(String.join("\n", pages)).contains("15. ");
    }

    @Test
    void reportsAllMaxedInsteadOfRecommendingFurtherRanks() {
        ObjectNode data = gear();
        data.path(ITEMS).forEach(row -> setRank((ObjectNode) row, 12846, 321));
        stub(data);

        assertThat(service.summary(PLAYER)).contains("No verified standard upgrades", "Maxed: 15");
        assertThat(String.join("\n", service.details(PLAYER))).contains("✅ Maxed", "Hero 6/6").doesNotContain("7/6");
    }

    @Test
    void reportsUnknownAndCraftedWithoutInventingCosts() {
        ObjectNode data = gear();
        data.path(ITEMS).forEach(row -> ((ObjectNode) row).remove(BONUSES));
        item(data, HEAD).putObject(CRAFTED).put(ID, 123);
        stub(data);

        assertThat(service.summary(PLAYER)).contains("Crafted: 1", "Unknown track: 14");
        assertThat(String.join("\n", service.details(PLAYER)))
                .contains("Unknown track", "recrafting order").doesNotContain(HERO_CRESTS);
    }

    @Test
    void upstreamEquipmentFailureIsSafeAndRetried() {
        when(api.profileQuery()).thenReturn(Map.of("namespace", "profile-eu"));
        when(api.getSnapshot(anyString(), anyMap(), anyMap()))
                .thenThrow(new RestClientException("secret=do-not-display"));

        assertThat(service.summary(PLAYER)).contains("Unavailable").doesNotContain("secret");
        assertThat(service.details(PLAYER)).singleElement().satisfies(page ->
                assertThat(page).contains("Unavailable").doesNotContain("secret"));
        verify(api, times(2)).getSnapshot(anyString(), anyMap(), anyMap());
    }

    private void stub(ObjectNode data) {
        when(api.profileQuery()).thenReturn(Map.of("namespace", "profile-eu", "locale", "en_GB"));
        when(api.getSnapshot(anyString(), anyMap(), anyMap()))
                .thenReturn(new BlizzardApiClient.ApiSnapshot(data, UPDATED, UPDATED));
        ObjectNode profile = JSON.createObjectNode();
        profile.putObject("active_spec").put(ID, 63).put(NAME, "Fire");
        profile.putObject("character_class").put(NAME, "Mage");
        when(api.get(anyString(), anyMap(), anyMap())).thenReturn(profile);
    }

    private static ObjectNode gear() {
        ObjectNode data = JSON.createObjectNode();
        for (String slot : List.of(HEAD, "NECK", "SHOULDER", "BACK", "CHEST", "WRIST", "HANDS", "WAIST", "LEGS",
                "FEET", "FINGER_1", "FINGER_2", "TRINKET_1", "TRINKET_2", MAIN_HAND)) {
            addItem(data, slot);
        }
        return data;
    }

    private static void addItem(ObjectNode data, String slot) {
        ObjectNode item = data.withArray(ITEMS).addObject().put(NAME, slot + " item");
        item.putObject("slot").put("type", slot);
        item.putObject("item").put(ID, 123);
        item.putObject("item_class").put(ID, slot.equals(MAIN_HAND) || slot.equals(OFF_HAND) ? 2 : 4);
        setRank(item, 12841, 305);
    }

    private static void setRank(ObjectNode item, int bonus, int level) {
        item.putArray(BONUSES).add(bonus);
        item.withObject(LEVEL).put(VALUE, level);
    }

    private static ObjectNode item(ObjectNode data, String slot) {
        for (JsonNode row : data.path(ITEMS)) {
            if (row.path("slot").path("type").asText().equals(slot)) {
                return (ObjectNode) row;
            }
        }
        throw new IllegalArgumentException(slot);
    }

    private static EquipmentSnapshot snapshot(ObjectNode data) {
        Map<String, JsonNode> items = new LinkedHashMap<>();
        data.path(ITEMS).forEach(row -> items.put(row.path("slot").path("type").asText(), row));
        return new EquipmentSnapshot(items, UPDATED);
    }
}
