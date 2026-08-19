package com.blackbox.wow.repository;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MPlusProgressRepository {

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
                        SELECT profile_id, season_key, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                        ORDER BY captured_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("profileId", profileId)
                .query(MPlusProgressRepository::mapScorePoint)
                .optional();
    }

    public Optional<ScorePoint> latestScoreAtOrBefore(
            long profileId,
            String season,
            Instant boundary
    ) {
        return jdbc.sql("""
                        SELECT profile_id, season_key, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                          AND season_key = :season
                          AND captured_at <= :boundary
                        ORDER BY captured_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("profileId", profileId)
                .param("season", season)
                .param("boundary", boundary)
                .query(MPlusProgressRepository::mapScorePoint)
                .optional();
    }

    public Optional<ScorePoint> firstScore(long profileId, String season) {
        return jdbc.sql("""
                        SELECT profile_id, season_key, score_all, captured_at,
                               region, realm, character_name
                        FROM mplus_score_snapshot
                        WHERE profile_id = :profileId
                          AND season_key = :season
                        ORDER BY captured_at ASC, id ASC
                        LIMIT 1
                        """)
                .param("profileId", profileId)
                .param("season", season)
                .query(MPlusProgressRepository::mapScorePoint)
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
                .param("profileId", profileId)
                .param("season", season)
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
                .param("profileId", player.profileId())
                .param("season", observation.season())
                .param("milestone", milestone)
                .param("score", score)
                .param("region", observation.region())
                .param("realm", observation.realm())
                .param("characterName", observation.name())
                .param("observedAt", observedAt)
                .update();
    }

    private static ScorePoint mapScorePoint(java.sql.ResultSet resultSet, int ignoredRowNumber)
            throws java.sql.SQLException {
        return new ScorePoint(
                resultSet.getLong("profile_id"),
                resultSet.getString("season_key"),
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
            BigDecimal score,
            Instant capturedAt,
            String region,
            String realm,
            String characterName
    ) {
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
}
