package com.blackbox.wow.service;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Fixed Midnight reagent baskets. IDs verified against Wowhead item data; no runtime item lookups. */
@Component
public class MaterialAlertCatalog {
    private final Map<String, List<Material>> groups;

    public MaterialAlertCatalog() {
        var ores = materials(
                "Refulgent Copper Ore", 237359L, 237361L, "Umbral Tin Ore", 237362L, 237363L,
                "Brilliant Silver Ore", 237364L, 237365L, "Dazzling Thorium", 237366L);
        var herbs = materials("Tranquility Bloom", 236761L, 236767L, "Sanguithorn", 236770L, 236771L,
                "Azeroot", 236774L, 236775L, "Argentleaf", 236776L, 236777L,
                "Mana Lily", 236778L, 236779L, "Nocturnal Lotus", 236780L);
        var skinning = materials("Void-Tempered Leather", 238511L, 238512L,
                "Void-Tempered Scales", 238513L, 238514L, "Void-Tempered Hide", 238518L, 238519L,
                "Void-Tempered Plating", 238520L, 238521L, "Carving Canine", 238523L,
                "Fantastic Fur", 238525L, "Majestic Claw", 238528L, "Majestic Hide", 238529L, "Majestic Fin", 238530L);
        var cloth = materials("Bright Linen", 236963L, 236965L);
        var motes = materials("Mote of Light", 236949L, "Mote of Primal Energy", 236950L,
                "Mote of Wild Magic", 236951L, "Mote of Pure Void", 236952L);
        var baskets = new LinkedHashMap<String, List<Material>>();
        baskets.put("ores", ores);
        baskets.put("herbs", herbs);
        baskets.put("skinning", skinning);
        baskets.put("cloth", cloth);
        baskets.put("leatherworking", combine(skinning, materials("Scalewoven Hide", 244631L, 244632L,
                "Infused Scalewoven Hide", 244633L, 244634L)));
        baskets.put("tailoring", combine(cloth, materials("Bright Linen Bolt", 239700L, 239701L,
                "Imbued Bright Linen Bolt", 239702L, 239703L, "Arcanoweave Bolt", 239198L, 239200L,
                "Sunfire Silk Bolt", 239201L, 239202L)));
        baskets.put("enchanting", materials("Eversinging Dust", 243599L, 243600L,
                "Radiant Shard", 243602L, 243603L, "Dawn Crystal", 243605L, 243606L));
        baskets.put("blacksmithing", combine(ores, materials("Refulgent Copper Ingot", 238197L, 238198L,
                "Gloaming Alloy", 238202L, 238203L, "Sterling Alloy", 238204L, 238205L)));
        baskets.put("engineering", materials("Song Gear", 243574L, 243575L, "Soul Sprocket", 243576L, 243577L,
                "Aetherlume", 243578L, 243579L, "Evercore", 243581L, 243582L));
        baskets.put("inscription", combine(herbs, materials("Munsell Ink", 245801L, 245802L,
                "Argentleaf Pigment", 245803L, 245804L, "Powder Pigment", 245807L, 245808L, "Sienna Ink", 245805L, 245806L,
                "Codified Azeroot", 245764L, 245765L, "Soul Cipher", 245766L, 245767L)));
        var gems = materials("Harandar Peridot", 242607L, 242720L, "Tenebrous Amethyst", 242606L, 242721L,
                "Amani Lapis", 242554L, 242722L, "Sanguine Garnet", 242553L, 242723L,
                "Flawless Harandar Peridot", 242610L, 242726L, "Flawless Tenebrous Amethyst", 242611L, 242725L,
                "Flawless Amani Lapis", 242612L, 242727L, "Flawless Sanguine Garnet", 242613L, 242724L,
                "Eversong Diamond", 242608L);
        baskets.put("jewelcrafting", combine(combine(ores, gems), materials("Glimmering Gemdust", 242620L, 242621L,
                "Sin'dorei Lens", 240972L, 240973L, "Dusk-Shrouded Stone", 242788L, 242789L,
                "Crystalline Glass", 242786L, 242787L)));
        baskets.put("alchemy", combine(herbs, materials("Wondrous Synergist", 241282L, 241283L)));
        baskets.put("elementals", motes);
        var fish = materials("Arcane Wyrmfish", 238371L, "Lynxfish", 238366L, "Root Crab", 238367L,
                "Sin'dorei Swarmer", 238365L, "Blood Hunter", 238377L, "Bloomtail Minnow", 238369L,
                "Shimmer Spinefish", 238370L, "Fungalskin Pike", 238375L, "Gore Guppy", 238382L,
                "Restored Songfish", 238372L, "Shimmersiren", 238378L, "Sunwell Fish", 238384L,
                "Tender Lumifin", 238374L, "Eversong Trout", 238383L, "Lucky Loa", 238376L,
                "Warping Wise", 238379L, "Twisted Tetra", 238368L, "Ominous Octopus", 238373L,
                "Null Voidfish", 238380L, "Hollow Grouper", 238381L,
                "Polluted Puffer", 274594L, "Oozing Goby", 279091L, "Many-Eyed Flounder", 279100L,
                "Twin-Headed Snipefish", 279105L, "Giggling Skull", 279093L, "Loathsome Anglerfish", 279106L,
                "Grotesque Sturgeon", 279094L, "Coiled Stargorger", 274591L, "Ula'tek Snakehead", 274589L,
                "Blightswarmer", 274593L, "Toxic Tlhapi", 274588L, "Spotted Killifish", 274587L,
                "Dirty Darter", 274592L, "Sulfurous Sludgefish", 274590L);
        baskets.put("fishing", fish);
        baskets.put("cooking", combine(fish, materials("Practically Pork", 242639L, "Thalassian Fillet", 253403L)));
        groups = Collections.unmodifiableMap(baskets);
    }

    public Map<String, List<Material>> groups() { return groups; }
    public Set<Long> itemIds() {
        return groups.values().stream().flatMap(Collection::stream).map(Material::id).collect(Collectors.toUnmodifiableSet());
    }
    public String canonicalKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mining", "ore" -> "ores";
            case "herbalism", "herb" -> "herbs";
            case "lw" -> "leatherworking";
            default -> normalized;
        };
    }
    public List<Material> group(String key) {
        var result = groups.get(canonicalKey(key));
        if (result == null) throw new IllegalArgumentException("Unknown group. Use /matalert groups.");
        return result;
    }
    private static List<Material> materials(Object... entries) {
        var result = new ArrayList<Material>();
        String label = null;
        for (Object entry : entries) {
            if (entry instanceof String name) label = name;
            else result.add(new Material((Long) entry, Objects.requireNonNull(label)));
        }
        return List.copyOf(result);
    }
    private static List<Material> combine(List<Material> first, List<Material> second) {
        var result = new ArrayList<>(first); result.addAll(second); return List.copyOf(result);
    }
    public record Material(long id, String name) {
        public String label() { return name + " [" + id + "]"; }
    }
}
