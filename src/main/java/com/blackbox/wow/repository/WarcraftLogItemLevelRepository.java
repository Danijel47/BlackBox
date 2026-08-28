package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;

import static com.blackbox.wow.helper.JdbcTimestampMapper.toUtcOffset;

@Repository
public class WarcraftLogItemLevelRepository {

    private static final String PARAM_PROFILE_ID = "profileId";
    private static final String PARAM_CHARACTER_NAME = "characterName";
    private static final String PARAM_OBSERVED_DATE = "observedDate";

    private final JdbcClient jdbc;

    public WarcraftLogItemLevelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasSnapshot(long profileId, String characterName, LocalDate observedDate) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM warcraft_log_item_level_snapshot
                            WHERE profile_id = :profileId
                              AND LOWER(character_name) = LOWER(:characterName)
                              AND observed_date = :observedDate
                        )
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_CHARACTER_NAME, characterName)
                .param(PARAM_OBSERVED_DATE, observedDate)
                .query(Boolean.class)
                .single());
    }

    public void save(
            long profileId,
            String characterName,
            LocalDate observedDate,
            Instant observedAt,
            BigDecimal itemLevel,
            String reportCode,
            int fightId
    ) {
        jdbc.sql("""
                        INSERT INTO warcraft_log_item_level_snapshot (
                            profile_id, character_name, observed_date, observed_at,
                            item_level, report_code, fight_id
                        ) VALUES (
                            :profileId, :characterName, :observedDate, :observedAt,
                            :itemLevel, :reportCode, :fightId
                        )
                        ON CONFLICT (profile_id, character_name, observed_date) DO UPDATE SET
                            character_name = EXCLUDED.character_name,
                            observed_at = EXCLUDED.observed_at,
                            item_level = EXCLUDED.item_level,
                            report_code = EXCLUDED.report_code,
                            fight_id = EXCLUDED.fight_id,
                            captured_at = CURRENT_TIMESTAMP
                        WHERE EXCLUDED.observed_at < warcraft_log_item_level_snapshot.observed_at
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_CHARACTER_NAME, characterName)
                .param(PARAM_OBSERVED_DATE, observedDate)
                .param("observedAt", toUtcOffset(observedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("itemLevel", itemLevel, Types.NUMERIC)
                .param("reportCode", reportCode)
                .param("fightId", fightId)
                .update();
    }
}
