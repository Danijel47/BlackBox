package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlizzardApiClientTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Sun, 06 Sep 2026 10:00:00 GMT", "invalid-date"})
    void retainsTheSourceTimestampWithoutInventingOneForMissingOrInvalidHeaders(String lastModified) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var response = withSuccess("{\"auctions\":[]}", MediaType.APPLICATION_JSON);
        if (lastModified != null) {
            response.header(HttpHeaders.LAST_MODIFIED, lastModified);
        }
        server.expect(requestTo("https://example.test/auctions")).andRespond(response);
        BlizzardAuthService auth = mock(BlizzardAuthService.class);
        when(auth.getAccessToken()).thenReturn("test-access-token");
        var client = new BlizzardApiClient(builder.build(), auth, null, JsonMapper.builder().build());

        var snapshot = client.getSnapshot("/auctions", null, null);

        assertThat(snapshot.data().path("auctions").isArray()).isTrue();
        assertThat(snapshot.fetchedAt()).isNotNull();
        Instant expected = lastModified != null && lastModified.startsWith("Sun")
                ? Instant.parse("2026-09-06T10:00:00Z") : null;
        assertThat(snapshot.sourceUpdatedAt()).isEqualTo(expected);
        server.verify();
    }

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
