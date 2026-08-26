package com.blackbox.wow.warcraftlogs;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dungeon-scoped avoidable ability catalogue for Midnight Mythic+ Season 2.
 *
 * <p>The catalogue is intentionally conservative at the event level: Warcraft Logs damage events
 * are counted only when their ability ID is explicitly classified for the fight's dungeon. The
 * source catalogue is Tactyks' Method ability tracker, updated 2026-08-18, where these abilities
 * carry the {@code Avoid} classification.</p>
 *
 * @see <a href="https://www.method.gg/guides/dungeons/voidscar-arena/ability-tracker">
 *     Method Mythic+ ability tracker</a>
 */
final class MidnightSeason2AvoidableAbilities {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Map<String, Set<Long>> ABILITIES_BY_DUNGEON = Map.of(
            normalize("Altar of Fangs"), Set.of(
                    1306235L, // Septic Spatter
                    1307894L, // Ravenous Stomp
                    1296220L, // Triple Shot
                    1296058L, // Regurgitate
                    1307526L, // Bloodletting (Bloodletter)
                    1306383L, // Infused Eggs
                    1305393L, // Undermining
                    1308865L, // Infest
                    1295055L, // Virulent Whirl
                    1301217L, // Bloodletting (Zul'jan)
                    1301114L  // Axegrinder
            ),
            normalize("Den of Nalorakk"), Set.of(
                    1297699L, // Rotten Supplies
                    1234021L, // Earthshatter Slam
                    1252825L, // Harsh Winds
                    1239860L, // Cryo Surge
                    1266178L, // Snowdrift
                    1240280L, // Pulverize
                    1235623L, // Raging Squall
                    1235783L, // Shattering Frostspike
                    1246986L, // Poison Spear Volley
                    1296722L, // Earthquake
                    1242860L, // Echoing Maul
                    1255577L  // Spectral Slash
            ),
            normalize("King's Rest"), Set.of(
                    273434L,  // Pit of Despair
                    1305945L, // Shadow Whirlwind
                    265773L,  // Spit Gold
                    270889L,  // Overload
                    1306056L, // Erupting Slam
                    270927L,  // Bladestorm
                    1305982L, // Shadow Volley
                    270293L,  // Purification Strike
                    271563L,  // Embalming Fluid
                    267639L,  // Burn Corruption
                    267618L,  // Drain Fluids
                    270482L,  // Violent Lunge
                    270514L,  // Seismic Upheaval
                    266206L,  // Whirling Axes
                    267060L,  // Call of the Elements
                    1298304L, // Dark Revelation
                    1302945L, // Impaling Spear
                    268932L   // Quaking Leap
            ),
            normalize("Murder Row"), Set.of(
                    1214966L, // Fel Infused
                    1256299L, // Over-infused
                    1223906L, // Fel Nova
                    474765L,  // Same-Day Delivery
                    1214357L, // Fire Bomb
                    1297691L, // Whirlwind
                    1297695L, // Felfire Bombardment
                    1295455L, // Infernal Crush
                    474197L,  // Demonic Rage
                    1294824L, // Defiled Slam
                    1215985L, // Fel Beam
                    1220899L, // Summon Infernal
                    474457L,  // Fingers of Gul'dan
                    1217384L  // Malefic Wave
            ),
            normalize("Ruby Life Pools"), Set.of(
                    1305201L, // Excavating Blast
                    372047L,  // Steel Barrage
                    1307372L, // Fiery Demise
                    396044L,  // Hailburst
                    372851L,  // Chillstorm
                    373693L,  // Living Bomb
                    373972L,  // Blaze of Glory
                    373614L,  // Burnout (Blazebound Destroyer)
                    372863L,  // Ritual of Blazebinding
                    372107L,  // Molten Boulder
                    373087L,  // Burnout (Blazebound Firestorm)
                    392399L,  // Stormcloud Detonation
                    381602L   // Inferno Spit
            ),
            normalize("Temple of Sethraliss"), Set.of(
                    1292585L, // Sandburst Arrow
                    1288864L, // Tempest Winds
                    1288235L, // Thunder and Lightning
                    1310396L, // Serpent's Stormcall
                    1293048L, // Thunder Spit
                    264172L,  // Burrow
                    1291622L, // Storm Catalyst
                    1293650L, // Call Lightning
                    264763L,  // Spark Step
                    274006L,  // Lightning Spire
                    1290531L, // Induction
                    1308546L, // Venomous Slash
                    1302153L, // Latent Hex
                    1301253L, // Agony of Sethraliss
                    1300871L  // Corrupted Lifeforce
            ),
            normalize("The Blinding Vale"), Set.of(
                    1237855L, // Earthrupture Strike
                    1263636L, // Belch Spores
                    1238368L, // Lightmaw Beams
                    1234753L, // Bedrock Slam
                    1261011L, // Fan of Thorns
                    1235814L, // Light-Scorched Earth
                    1236658L, // Bloodthorn Roots
                    1236709L, // Thorncaller Roar
                    1239824L, // Lightfire
                    1240098L, // Lightfall
                    1242180L, // Lightwarden's Blight
                    1246607L  // Concentrated Lightbeam
            ),
            normalize("Voidscar Arena"), Set.of(
                    1250640L, // Venomous Spit
                    1228126L, // Macestorm
                    1299145L, // Earthsplitter
                    1299913L, // Null Eruption
                    1299270L, // Thundering Storm
                    1250079L, // Ravenous Swarm
                    1249238L, // Fire Spit
                    1296963L, // Umbral Rupture
                    1300259L, // Dark Bloom
                    1233264L, // Blisterburst
                    1239856L, // Sky Strike
                    1226031L, // Poison Splash
                    1227197L  // Cosmic Crash
            )
    );

    private MidnightSeason2AvoidableAbilities() {
    }

    static boolean contains(String dungeonName, long abilityGameId) {
        return abilitiesFor(dungeonName).orElseGet(Set::of).contains(abilityGameId);
    }

    static Optional<Set<Long>> abilitiesFor(String dungeonName) {
        return Optional.ofNullable(ABILITIES_BY_DUNGEON.get(normalize(dungeonName)));
    }

    static int dungeonCount() {
        return ABILITIES_BY_DUNGEON.size();
    }

    static int abilityCount() {
        return ABILITIES_BY_DUNGEON.values().stream().mapToInt(Set::size).sum();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NON_ALPHANUMERIC.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
