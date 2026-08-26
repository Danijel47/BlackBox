package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class BlizzardApiClient {

    private static final String NAMESPACE_QUERY_PARAM = "namespace";
    private static final String DYNAMIC_NAMESPACE_PREFIX = "dynamic-";
    private static final String STATIC_NAMESPACE_PREFIX = "static-";
    private static final String PROFILE_NAMESPACE_PREFIX = "profile-";

    private final RestClient apiClient;
    private final BlizzardAuthService authService;
    private final BlizzardApiProperties props;
    private final JsonMapper json;

    public BlizzardApiClient(
            @Qualifier("blizzardApiRestClient") RestClient apiClient,
            BlizzardAuthService authService,
            BlizzardApiProperties props,
            JsonMapper json
    ) {
        this.apiClient = apiClient;
        this.authService = authService;
        this.props = props;
        this.json = json;
    }

    public JsonNode get(String path, Map<String, ?> uriVars, Map<String, ?> query) {
        String token = authService.getAccessToken();
        String body = apiClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path(path);
                    if (query != null) {
                        query.forEach(builder::queryParam);
                    }
                    return uriVars == null ? builder.build() : builder.build(uriVars);
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }

        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Blizzard API response", e);
        }
    }

    public Map<String, String> defaultQuery() {
        return Map.of(
                NAMESPACE_QUERY_PARAM, props.namespace(),
                "locale", props.locale()
        );
    }

    public Map<String, String> profileQuery() {
        Map<String, String> query = new HashMap<>(defaultQuery());
        query.put(NAMESPACE_QUERY_PARAM, toProfileNamespace(query.get(NAMESPACE_QUERY_PARAM)));
        return Map.copyOf(query);
    }

    private static String toProfileNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return PROFILE_NAMESPACE_PREFIX + "eu";
        }
        if (namespace.startsWith(DYNAMIC_NAMESPACE_PREFIX)) {
            return PROFILE_NAMESPACE_PREFIX + namespace.substring(DYNAMIC_NAMESPACE_PREFIX.length());
        }
        if (namespace.startsWith(STATIC_NAMESPACE_PREFIX)) {
            return PROFILE_NAMESPACE_PREFIX + namespace.substring(STATIC_NAMESPACE_PREFIX.length());
        }
        return namespace;
    }
}
