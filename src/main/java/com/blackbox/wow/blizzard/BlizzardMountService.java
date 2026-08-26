package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class BlizzardMountService {

    private final BlizzardApiClient api;
    private final BlizzardApiProperties props;
    private final BlizzardCache<MountProgress> mountCache;

    public BlizzardMountService(BlizzardApiClient api, BlizzardApiProperties props) {
        this.api = api;
        this.props = props;
        this.mountCache = new BlizzardCache<>(Math.max(1, props.cache().maxItemEntries()), value -> 1);
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
                api.profileQuery());

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

    private static String slug(String value) {
        if (value == null) return "";
        return value.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    public record MountProgress(String characterName, String realmSlug, int usable, int collected) {}
}
