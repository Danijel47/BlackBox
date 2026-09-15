package com.blackbox.wow.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RaiderIoClientTest {

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

        assertThat(RaiderIoClient.retryAfterSeconds(rateLimited)).isEqualTo(1);
    }
}
