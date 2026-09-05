package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BlizzardApiClientTest {

    @Test
    void derivesTheStaticNamespaceFromTheConfiguredRegion() {
        BlizzardApiProperties properties = new BlizzardApiProperties(
                null,
                null,
                null,
                null,
                "dynamic-us",
                "en_US",
                null
        );
        BlizzardApiClient client = new BlizzardApiClient(
                mock(RestClient.class),
                mock(BlizzardAuthService.class),
                properties,
                JsonMapper.builder().build()
        );

        assertThat(client.staticQuery())
                .containsEntry("namespace", "static-us")
                .containsEntry("locale", "en_US");
        assertThat(client.staticSearchQuery())
                .containsExactlyEntriesOf(Map.of("namespace", "static-us"));
    }
}
