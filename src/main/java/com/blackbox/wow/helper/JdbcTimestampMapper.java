package com.blackbox.wow.helper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class JdbcTimestampMapper {

    private JdbcTimestampMapper() {
    }

    public static OffsetDateTime toUtcOffset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
