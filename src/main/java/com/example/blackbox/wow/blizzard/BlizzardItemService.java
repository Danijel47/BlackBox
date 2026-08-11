package com.example.blackbox.wow.blizzard;

import com.example.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BlizzardItemService {

    private final BlizzardApiClient api;
    private final BlizzardApiProperties props;

    private final BlizzardCache<ItemRef> byNameCache;
    private final BlizzardCache<ItemRef> byIdCache;
    private final BlizzardCache<List<ItemRef>> exactByNameCache;

    public BlizzardItemService(BlizzardApiClient api, BlizzardApiProperties props) {
        this.api = api;
        this.props = props;
        long maximumEntries = Math.max(1, props.cache().maxItemEntries());
        this.byNameCache = new BlizzardCache<>(maximumEntries, value -> 1);
        this.byIdCache = new BlizzardCache<>(maximumEntries, value -> 1);
        this.exactByNameCache = new BlizzardCache<>(maximumEntries, values -> Math.max(1, values.size()));
    }

    public ItemRef findByName(String itemName) {
        String normalized = normalize(itemName);
        if (normalized.isBlank()) return null;

        Duration ttl = Duration.ofSeconds(props.cache().realmTtlSeconds());
        return byNameCache.getOrCompute("name:" + normalized, ttl, () -> findByNameInternal(normalized));
    }

    public ItemRef findByNameRank(String itemName, int rank) {
        String normalized = normalize(itemName);
        if (normalized.isBlank() || rank <= 0) return null;

        Duration ttl = Duration.ofSeconds(props.cache().realmTtlSeconds());
        return byNameCache.getOrCompute("name:" + normalized + ":rank:" + rank, ttl, () -> findByNameRankInternal(normalized, rank));
    }

    public List<ItemRef> findExactByName(String itemName) {
        String normalized = normalize(itemName);
        if (normalized.isBlank()) return List.of();

        Duration ttl = Duration.ofSeconds(props.cache().realmTtlSeconds());
        return exactByNameCache.getOrCompute("name:" + normalized + ":exact", ttl, () -> findExactMatches(normalized));
    }

    public ItemRef getById(long itemId) {
        if (itemId <= 0) return null;

        Duration ttl = Duration.ofSeconds(props.cache().realmTtlSeconds());
        return byIdCache.getOrCompute("id:" + itemId, ttl, () -> getByIdInternal(itemId));
    }

    private ItemRef findByNameInternal(String queryName) {
        List<ItemRef> exactMatches = findExactMatches(queryName);
        return exactMatches.isEmpty() ? null : exactMatches.getFirst();
    }

    private ItemRef findByNameRankInternal(String queryName, int rank) {
        List<ItemRef> exactMatches = findExactMatches(queryName);
        if (exactMatches.size() < rank) return null;
        return exactMatches.get(rank - 1);
    }

    private List<ItemRef> findExactMatches(String queryName) {
        Map<String, Object> query = new HashMap<>(defaultStaticQuery());
        query.put("orderby", "id:asc");
        query.put("name." + props.locale(), queryName);

        JsonNode payload = api.get("/data/wow/search/item", null, query);
        JsonNode results = payload.path("results");
        if (!results.isArray() || results.isEmpty()) return List.of();

        String queryKey = normalizeNameKey(queryName);
        List<ItemRef> exactMatches = new ArrayList<>();
        for (JsonNode entry : results) {
            JsonNode data = entry.path("data");
            long id = data.path("id").asLong(0);
            if (id <= 0) continue;

            String name = extractName(data);
            if (name.isBlank()) continue;

            ItemRef current = new ItemRef(id, name);
            if (normalizeNameKey(name).equals(queryKey)) {
                exactMatches.add(current);
            }
        }
        exactMatches.sort(Comparator.comparingLong(ItemRef::id));
        return exactMatches;
    }

    private ItemRef getByIdInternal(long itemId) {
        JsonNode data = api.get("/data/wow/item/{itemId}", Map.of("itemId", itemId), defaultStaticQuery());
        long id = data.path("id").asLong(0);
        if (id <= 0) return null;

        String name = extractName(data);
        if (name.isBlank()) {
            name = "item " + id;
        }
        return new ItemRef(id, name);
    }

    private Map<String, String> defaultStaticQuery() {
        Map<String, String> query = new HashMap<>(api.defaultQuery());
        query.put("namespace", toStaticNamespace(query.get("namespace")));
        return query;
    }

    private String extractName(JsonNode data) {
        JsonNode nameNode = data.path("name");
        if (nameNode.isTextual()) {
            return nameNode.asText("").trim();
        }

        String locale = props.locale();
        if (nameNode.isObject()) {
            String byLocale = nameNode.path(locale).asText("").trim();
            if (!byLocale.isBlank()) return byLocale;

            String byDashLocale = nameNode.path(locale.replace('_', '-')).asText("").trim();
            if (!byDashLocale.isBlank()) return byDashLocale;
        }

        return "";
    }

    private static String toStaticNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return "static-eu";
        if (namespace.startsWith("dynamic-")) {
            return "static-" + namespace.substring("dynamic-".length());
        }
        return namespace;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeNameKey(String value) {
        return normalize(value).toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    public record ItemRef(long id, String name) {}
}
