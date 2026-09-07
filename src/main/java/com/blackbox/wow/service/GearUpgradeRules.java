package com.blackbox.wow.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/** Seasonal data is intentionally separate from report rendering and recommendation ordering. */
public final class GearUpgradeRules {

    public static final String SEASON = "Midnight Season 2";
    public static final int CRESTS_PER_RANK = 20;
    public static final String GUIDE_URL =
            "https://www.wowhead.com/guide/midnight/item-level-gear-upgrades-dawncrests";
    // Verified 2026-09-07 against the guide and the published bonus-ID mapping:
    // https://github.com/consecrated-hammer/wow-site/blob/main/site/season-data.js
    // Both the bonus ID and its expected item level must agree. Never infer a track from ilvl alone.
    private static final List<Track> TRACKS = List.of(
            new Track("Adventurer", 12817, List.of(266, 269, 272, 276, 279, 282)),
            new Track("Veteran", 12825, List.of(279, 282, 285, 289, 292, 295)),
            new Track("Champion", 12833, List.of(292, 295, 298, 302, 305, 308)),
            new Track("Hero", 12841, List.of(305, 308, 311, 315, 318, 321)),
            new Track("Myth", 12849, List.of(318, 321, 324, 328, 331, 334))
    );

    private GearUpgradeRules() {
    }

    public static Optional<UpgradeStep> resolve(JsonNode bonusList, int itemLevel) {
        if (!bonusList.isArray()) {
            return Optional.empty();
        }
        UpgradeStep match = null;
        for (JsonNode bonus : bonusList) {
            if (!bonus.isIntegralNumber() || !bonus.canConvertToInt()) {
                return Optional.empty();
            }
            UpgradeStep candidate = findBonus(bonus.intValue());
            if (candidate != null) {
                if (candidate.currentLevel() != itemLevel || (match != null && !match.equals(candidate))) {
                    return Optional.empty();
                }
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    private static UpgradeStep findBonus(int bonusId) {
        for (Track track : TRACKS) {
            int index = bonusId - track.firstBonusId();
            if (index >= 0 && index < track.levels().size()) {
                return new UpgradeStep(track, index + 1);
            }
        }
        return null;
    }

    public record Track(String name, int firstBonusId, List<Integer> levels) {
        public Track {
            levels = List.copyOf(levels);
        }
    }

    public record UpgradeStep(Track track, int rank) {
        public int currentLevel() {
            return track.levels().get(rank - 1);
        }

        public boolean maxed() {
            return rank == track.levels().size();
        }

        public int nextLevel() {
            return maxed() ? currentLevel() : track.levels().get(rank);
        }

        public String label() {
            return track.name() + " " + rank + "/" + track.levels().size();
        }

        public String crest() {
            return track.name() + " Mistcrests";
        }
    }
}
