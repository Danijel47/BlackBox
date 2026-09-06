package com.blackbox.wow.repository;

import com.blackbox.wow.service.ProspectingBatch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.blackbox.wow.helper.JdbcTimestampMapper.toUtcOffset;

@Repository
public class ProspectingSampleRepository {

    private static final String USER_ID = "userId";
    private static final String ORE_ID = "oreId";
    private static final String SELECT_SAMPLES = """
            SELECT ore_id, batch_arguments, recorded_at FROM wow_prospecting_sample
            WHERE telegram_user_id = :userId
            """;
    private static final RowMapper<SavedSample> SAMPLE_MAPPER = (row, _) -> new SavedSample(
            ProspectingBatch.parse(row.getString("batch_arguments")),
            row.getObject("recorded_at", OffsetDateTime.class).toInstant()
    );
    private final JdbcClient jdbc;

    public ProspectingSampleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long userId, ProspectingBatch batch, Instant recordedAt) {
        jdbc.sql("""
                INSERT INTO wow_prospecting_sample (telegram_user_id, ore_id, batch_arguments, recorded_at)
                VALUES (:userId, :oreId, :arguments, :recordedAt)
                ON CONFLICT (telegram_user_id, ore_id) DO UPDATE SET
                    batch_arguments = EXCLUDED.batch_arguments, recorded_at = EXCLUDED.recorded_at
                """)
                .param(USER_ID, userId).param(ORE_ID, batch.oreId())
                .param("arguments", arguments(batch)).param("recordedAt", toUtcOffset(recordedAt)).update();
    }

    public Optional<SavedSample> find(long userId, long oreId) {
        return jdbc.sql(SELECT_SAMPLES + " AND ore_id = :oreId")
                .param(USER_ID, userId).param(ORE_ID, oreId)
                .query(SAMPLE_MAPPER).optional();
    }

    public List<SavedSample> findAll(long userId) {
        return jdbc.sql(SELECT_SAMPLES + " ORDER BY ore_id").param(USER_ID, userId)
                .query(SAMPLE_MAPPER).list();
    }

    public static String arguments(ProspectingBatch batch) {
        return batch.oreId() + " " + batch.oreQuantity() + " " + batch.outputs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(" "));
    }

    public record SavedSample(ProspectingBatch batch, Instant recordedAt) {}
}
