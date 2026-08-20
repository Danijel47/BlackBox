package com.blackbox.wow.helper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTimestampMapperTest {

    @Test
    void mapsInstantToPostgresSupportedUtcOffsetDateTime() {
        Instant timestamp = Instant.parse("2026-08-20T11:09:19.229Z");

        assertThat(JdbcTimestampMapper.toUtcOffset(timestamp))
                .isEqualTo(timestamp.atOffset(ZoneOffset.UTC));
        assertThat(JdbcTimestampMapper.toUtcOffset(timestamp).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(JdbcTimestampMapper.toUtcOffset(null)).isNull();
    }
}
