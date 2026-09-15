package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WarcraftLogsClientTest {

    @Test
    void parsesRetryAfterSecondsAndFallsBackForInvalidValues() {
        assertThat(WarcraftLogsClient.parseRetryAfter("1800")).isEqualTo(Duration.ofMinutes(30));
        assertThat(WarcraftLogsClient.parseRetryAfter("999999"))
                .isEqualTo(Duration.ofHours(24));
        assertThat(WarcraftLogsClient.parseRetryAfter(null)).isEqualTo(Duration.ofHours(1));
        assertThat(WarcraftLogsClient.parseRetryAfter("invalid")).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void usesDefaultRetryDelayWhenRateLimitResponseHasNoHeaders() {
        HttpClientErrorException.TooManyRequests rateLimited =
                (HttpClientErrorException.TooManyRequests) HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests",
                        null,
                        new byte[0],
                        StandardCharsets.UTF_8
                );

        assertThat(WarcraftLogsClient.retryAfter(rateLimited)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsRequestsLocallyWhileTheRateLimitBackoffIsActive() {
        WarcraftLogsProperties properties = mock(WarcraftLogsProperties.class);
        WarcraftLogsAuthService authService = mock(WarcraftLogsAuthService.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        WarcraftLogsClient client = new WarcraftLogsClient(properties, authService, jsonMapper);
        AtomicReference<Instant> blockedUntil = rateLimitedUntil(client);
        blockedUntil.set(Instant.now().plus(Duration.ofMinutes(30)));

        assertThatThrownBy(() -> client.query("query Test { test }", Map.of()))
                .isInstanceOf(WarcraftLogsClient.RateLimitExceededException.class)
                .hasMessage("Warcraft Logs request limit was reached.");
        verifyNoInteractions(properties, authService, jsonMapper);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Instant> rateLimitedUntil(WarcraftLogsClient client) {
        Object value = ReflectionTestUtils.getField(client, "rateLimitedUntil");
        assertThat(value).isInstanceOf(AtomicReference.class);
        return (AtomicReference<Instant>) value;
    }
}
