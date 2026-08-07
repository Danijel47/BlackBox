package com.example.blackbox.wow.blizzard;

import com.example.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class BlizzardMountService {

    private final BlizzardApiClient api;
    private final BlizzardApiProperties props;
    private final BlizzardCache<MountProgress> mountCache = new BlizzardCache<>();

    public BlizzardMountService(BlizzardApiClient api, BlizzardApiProperties props) {
        this.api = api;
        this.props = props;
    }

    public MountProgress getMountProgress(String realm, String characterName) {
        String realmSlug = slug(realm);
        String characterSlug = slug(characterName);
        if (realmSlug.isBlank() || characterSlug.isBlank()) {
            throw new IllegalArgumentException("Realm and character name are required");
        }

        Duration ttl = Duration.ofSeconds(props.cache().realmTtlSeconds());
        String key = realmSlug + ":" + characterSlug;
        return mountCache.getOrCompute(key, ttl, () -> getMountProgressInternal(realmSlug, characterSlug));
    }

    private MountProgress getMountProgressInternal(String realmSlug, String characterSlug) {
        JsonNode data = api.get(
                "/profile/wow/character/{realmSlug}/{characterName}/collections/mounts",
                Map.of("realmSlug", realmSlug, "characterName", characterSlug),
                profileQuery());

        JsonNode mounts = data.path("mounts");
        int collected = mounts.isArray() ? mounts.size() : 0;
        int usable = countUsableMounts(mounts);
        String resolvedName = data.path("character").path("name").asText(characterSlug);
        String resolvedRealm = data.path("character").path("realm").path("slug").asText(realmSlug);

        return new MountProgress(resolvedName, resolvedRealm, usable, collected);
    }

    private static int countUsableMounts(JsonNode mounts) {
        if (!mounts.isArray()) return 0;

        int usable = 0;
        for (JsonNode mount : mounts) {
            if (mount.path("is_useable").asBoolean(true)) {
                usable++;
            }
        }
        return usable;
    }

    private Map<String, String> profileQuery() {
        Map<String, String> query = new HashMap<>(api.defaultQuery());
        query.put("namespace", toProfileNamespace(query.get("namespace")));
        return query;
    }

    private static String toProfileNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return "profile-eu";
        if (namespace.startsWith("dynamic-")) {
            return "profile-" + namespace.substring("dynamic-".length());
        }
        if (namespace.startsWith("static-")) {
            return "profile-" + namespace.substring("static-".length());
        }
        return namespace;
    }

    private static String slug(String value) {
        if (value == null) return "";
        return value.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    public record MountProgress(String characterName, String realmSlug, int usable, int collected) {}
}
