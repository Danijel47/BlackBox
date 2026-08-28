package com.blackbox.wow.repository;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.blackbox.wow.helper.JdbcTimestampMapper.toUtcOffset;

@Repository
public class MPlusProgressRepository {

    private static final String PARAM_PROFILE_ID = "profileId";
    private static final String PARAM_SEASON = "season";
    private static final String PARAM_CHARACTER_NAME = "characterName";
    private static final String PARAM_BOUNDARY = "boundary";

    private final JdbcClient jdbc;
    private final List<BigDecimal> milestoneScores;

    public MPlusProgressRepository(JdbcClient jdbc, MPlusProgressProperties properties) {
        this.jdbc = jdbc;
        this.milestoneScores = properties.milestones();
    }

    public void recordMilestones(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant observedAt
    ) {
        BigDecimal score = observation.scoreAll();
        if (score == null) {
            return;
        }
        for (BigDecimal milestone : milestoneScores) {
            if (score.compareTo(milestone) >= 0) {
                insertMilestone(player, observation, observedAt, milestone, score);
            }
        }
    }

    public Optional<ScorePoint> latestScore(long profileId) {
        return jdbc.sql("""
                        SELECT profile_id, season_key, item_level, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                        ORDER BY captured_at DESC, id DESC
                        LIMIT 1
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .query(MPlusProgressRepository::mapScorePoint)
                .optional();
    }

    public Optional<ScorePoint> latestScoreAtOrBefore(
            long profileId,
            String season,
            Instant boundary,
            String characterName
    ) {
        return jdbc.sql("""
                        SELECT profile_id, season_key, item_level, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                          AND season_key = :season
                          AND captured_at <= :boundary
                          AND LOWER(character_name) = LOWER(:characterName)
                        ORDER BY captured_at DESC, id DESC
                        LIMIT 1
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_SEASON, season)
                .param(PARAM_BOUNDARY, toUtcOffset(boundary), Types.TIMESTAMP_WITH_TIMEZONE)
                .param(PARAM_CHARACTER_NAME, characterName)
                .query(MPlusProgressRepository::mapScorePoint)
                .optional();
    }

    public Optional<ScorePoint> latestScoreAtOrBefore(long profileId, Instant boundary) {
        return jdbc.sql("""
                        SELECT profile_id, season_key, item_level, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                          AND captured_at <= :boundary
                        ORDER BY captured_at DESC, id DESC
                        LIMIT 1
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_BOUNDARY, toUtcOffset(boundary), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(MPlusProgressRepository::mapScorePoint)
                .optional();
    }

    public Optional<ScorePoint> firstScore(long profileId, String season, String characterName) {
        return jdbc.sql("""
                        SELECT profile_id, season_key, item_level, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                          AND season_key = :season
                          AND LOWER(character_name) = LOWER(:characterName)
                        ORDER BY captured_at ASC, id ASC
                        LIMIT 1
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_SEASON, season)
                .param(PARAM_CHARACTER_NAME, characterName)
                .query(MPlusProgressRepository::mapScorePoint)
                .optional();
    }

    public List<WeeklyRun> weeklyRuns(
            long profileId,
            String season,
            Instant periodStart,
            Instant periodEnd,
            String characterName
    ) {
        return jdbc.sql("""
                        SELECT run.mythic_level, run.timed
                        FROM mplus_observed_run run
                        JOIN mplus_observed_run_profile run_profile ON run_profile.run_id = run.id
                        WHERE run_profile.profile_id = :profileId
                          AND run.season_key = :season
                          AND LOWER(run_profile.observed_character_name) = LOWER(:characterName)
                          AND run.completed_at >= :periodStart
                          AND run.completed_at < :periodEnd
                        ORDER BY run.mythic_level DESC, run.completed_at DESC
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_SEASON, season)
                .param(PARAM_CHARACTER_NAME, characterName)
                .param("periodStart", toUtcOffset(periodStart), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("periodEnd", toUtcOffset(periodEnd), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((resultSet, ignoredRowNumber) -> new WeeklyRun(
                        resultSet.getInt("mythic_level"),
                        resultSet.getBoolean("timed")
                ))
                .list();
    }

    public Optional<BigDecimal> latestItemLevelAtOrBefore(
            long profileId,
            String characterName,
            Instant boundary
    ) {
        return jdbc.sql("""
                        SELECT history.item_level
                        FROM (
                            SELECT item_level, captured_at AS observed_at
                            FROM mplus_score_snapshot
                            WHERE profile_id = :profileId
                              AND item_level IS NOT NULL
                              AND LOWER(character_name) = LOWER(:characterName)
                            UNION ALL
                            SELECT item_level, observed_at
                            FROM warcraft_log_item_level_snapshot
                            WHERE profile_id = :profileId
                              AND LOWER(character_name) = LOWER(:characterName)
                        ) history
                        WHERE history.observed_at <= :boundary
                        ORDER BY history.observed_at DESC
                        LIMIT 1
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_CHARACTER_NAME, characterName)
                .param(PARAM_BOUNDARY, toUtcOffset(boundary), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(BigDecimal.class)
                .optional();
    }

    public List<Milestone> milestones(long profileId, String season) {
        return jdbc.sql("""
                        SELECT milestone_score, observed_score, first_observed_at,
                               region, realm, character_name
                        FROM mplus_score_milestone
                        WHERE profile_id = :profileId AND season_key = :season
                        ORDER BY milestone_score ASC
                        """)
                .param(PARAM_PROFILE_ID, profileId)
                .param(PARAM_SEASON, season)
                .query((resultSet, rowNumber) -> new Milestone(
                        resultSet.getBigDecimal("milestone_score"),
                        resultSet.getBigDecimal("observed_score"),
                        toInstant(resultSet.getObject("first_observed_at", OffsetDateTime.class)),
                        resultSet.getString("region"),
                        resultSet.getString("realm"),
                        resultSet.getString("character_name")
                ))
                .list();
    }

    private void insertMilestone(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant observedAt,
            BigDecimal milestone,
            BigDecimal score
    ) {
        jdbc.sql("""
                        INSERT INTO mplus_score_milestone (
                            profile_id, season_key, milestone_score, observed_score,
                            region, realm, character_name, first_observed_at
                        ) VALUES (
                            :profileId, :season, :milestone, :score,
                            :region, :realm, :characterName, :observedAt
                        )
                        ON CONFLICT (profile_id, season_key, milestone_score) DO NOTHING
                        """)
                .param(PARAM_PROFILE_ID, player.profileId())
                .param(PARAM_SEASON, observation.season())
                .param("milestone", milestone)
                .param("score", score)
                .param("region", observation.region())
                .param("realm", observation.realm())
                .param(PARAM_CHARACTER_NAME, observation.name())
                .param("observedAt", toUtcOffset(observedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static ScorePoint mapScorePoint(java.sql.ResultSet resultSet, int ignoredRowNumber)
            throws java.sql.SQLException {
        return new ScorePoint(
                resultSet.getLong("profile_id"),
                resultSet.getString("season_key"),
                resultSet.getBigDecimal("item_level"),
                resultSet.getBigDecimal("score_all"),
                toInstant(resultSet.getObject("captured_at", OffsetDateTime.class)),
                resultSet.getString("region"),
                resultSet.getString("realm"),
                resultSet.getString("character_name")
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record ScorePoint(
            long profileId,
            String season,
            BigDecimal itemLevel,
            BigDecimal score,
            Instant capturedAt,
            String region,
            String realm,
            String characterName
    ) {
        public ScorePoint(
                long profileId,
                String season,
                BigDecimal score,
                Instant capturedAt,
                String region,
                String realm,
                String characterName
        ) {
            this(profileId, season, null, score, capturedAt, region, realm, characterName);
        }
    }

    public record Milestone(
            BigDecimal milestoneScore,
            BigDecimal observedScore,
            Instant firstObservedAt,
            String region,
            String realm,
            String characterName
    ) {
    }

    public record WeeklyRun(int level, boolean timed) {
    }
}
