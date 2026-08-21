package com.blackbox.wow.service;

import com.blackbox.wow.client.WowTokenHistoryClient.HistoricalTokenPrice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

@Service
public class WowTokenHistoryImportPersistence {

    private static final String TOKEN_REGION = "EU";
    private static final int BATCH_SIZE = 250;
    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO wow_token_price_snapshot
                (region, price_copper, source_updated_at, captured_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (region, captured_at) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public WowTokenHistoryImportPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int insertIfMissing(List<HistoricalTokenPrice> prices) {
        int[][] updateCounts = jdbcTemplate.batchUpdate(
                INSERT_HISTORY_SQL,
                prices,
                BATCH_SIZE,
                (statement, price) -> {
                    Timestamp timestamp = Timestamp.from(price.capturedAt());
                    statement.setString(1, TOKEN_REGION);
                    statement.setLong(2, price.priceCopper());
                    statement.setTimestamp(3, timestamp);
                    statement.setTimestamp(4, timestamp);
                }
        );
        return Arrays.stream(updateCounts)
                .flatMapToInt(Arrays::stream)
                .map(updateCount -> updateCount == Statement.SUCCESS_NO_INFO
                        ? 1
                        : Math.max(0, updateCount))
                .sum();
    }
}
