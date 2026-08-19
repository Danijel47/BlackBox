package com.blackbox.wow.repository;

import com.blackbox.wow.helper.MPlusRunMatcher.MatchDecision;
import com.blackbox.wow.helper.MPlusRunMatcher.MatchEvidence;
import com.blackbox.wow.helper.MPlusRunMatcher.ObservedCandidate;
import com.blackbox.wow.helper.MPlusRunMatcher.PlayerIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class MPlusRunCorrelationRepository {

    private final JdbcClient jdbc;

    public MPlusRunCorrelationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ObservedCandidate> observedCandidates(String season, int mythicLevel) {
        List<CandidateRow> rows = jdbc.sql("""
                        SELECT run.id, run.season_key, run.dungeon_name, run.dungeon_short_name,
                               run.mythic_level, run.completed_at, run.clear_time_ms,
                               member.region, member.realm, member.character_name
                        FROM mplus_observed_run run
                        LEFT JOIN mplus_observed_run_member member ON member.run_id = run.id
                        WHERE run.season_key = :season AND run.mythic_level = :mythicLevel
                        ORDER BY run.id, member.id
                        """)
                .param("season", season)
                .param("mythicLevel", mythicLevel)
                .query(MPlusRunCorrelationRepository::mapCandidateRow)
                .list();
        return buildCandidates(rows);
    }

    public boolean hasDifferentMatchedFight(long runId, String reportCode, int fightId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM mplus_run_log_match
                        WHERE mplus_run_id = :runId
                          AND match_status = 'MATCHED'
                          AND (report_code <> :reportCode OR fight_id <> :fightId)
                        """)
                .param("runId", runId)
                .param("reportCode", reportCode)
                .param("fightId", fightId)
                .query(Integer.class)
                .single() > 0;
    }

    public void saveDecision(
            String season,
            String reportCode,
            int reportRevision,
            int fightId,
            MatchDecision decision
    ) {
        MatchEvidence evidence = decision.evidence();
        jdbc.sql("""
                        INSERT INTO mplus_run_log_match (
                            season_key, mplus_run_id, report_code, report_revision, fight_id,
                            match_status, match_method, candidate_count, timestamp_delta_ms,
                            duration_delta_ms, roster_overlap_count
                        ) VALUES (
                            :season, :runId, :reportCode, :revision, :fightId,
                            :status, 'AUTOMATIC', :candidateCount, :timestampDelta,
                            :durationDelta, :rosterOverlap
                        )
                        ON CONFLICT (season_key, report_code, fight_id) DO UPDATE SET
                            mplus_run_id = EXCLUDED.mplus_run_id,
                            report_revision = GREATEST(
                                mplus_run_log_match.report_revision, EXCLUDED.report_revision
                            ),
                            match_status = EXCLUDED.match_status,
                            match_method = EXCLUDED.match_method,
                            candidate_count = EXCLUDED.candidate_count,
                            timestamp_delta_ms = EXCLUDED.timestamp_delta_ms,
                            duration_delta_ms = EXCLUDED.duration_delta_ms,
                            roster_overlap_count = EXCLUDED.roster_overlap_count,
                            last_refreshed_at = CURRENT_TIMESTAMP
                        WHERE mplus_run_log_match.match_method <> 'MANUAL'
                        """)
                .param("season", season)
                .param("runId", decision.run() == null ? null : decision.run().runId())
                .param("reportCode", reportCode)
                .param("revision", reportRevision)
                .param("fightId", fightId)
                .param("status", decision.status().name())
                .param("candidateCount", decision.candidateCount())
                .param("timestampDelta", evidence == null ? null : evidence.timestampDeltaMs())
                .param("durationDelta", evidence == null ? null : evidence.durationDeltaMs())
                .param("rosterOverlap", evidence == null ? null : evidence.rosterOverlap())
                .update();
    }

    public Coverage coverage(long profileId, String season) {
        return jdbc.sql("""
                        SELECT COUNT(DISTINCT run.id) AS observed_runs,
                               COUNT(DISTINCT CASE WHEN match.match_status = 'MATCHED'
                                                  THEN run.id END) AS matched_runs
                        FROM mplus_observed_run run
                        JOIN mplus_observed_run_profile run_profile ON run_profile.run_id = run.id
                        LEFT JOIN mplus_run_log_match match ON match.mplus_run_id = run.id
                        WHERE run_profile.profile_id = :profileId AND run.season_key = :season
                        """)
                .param("profileId", profileId)
                .param("season", season)
                .query((resultSet, ignored) -> new Coverage(
                        resultSet.getInt("observed_runs"), resultSet.getInt("matched_runs")
                ))
                .single();
    }

    public List<StatusCount> statusCounts(String season) {
        return jdbc.sql("""
                        SELECT match_status, COUNT(*) AS status_count
                        FROM mplus_run_log_match
                        WHERE season_key = :season
                        GROUP BY match_status
                        ORDER BY match_status
                        """)
                .param("season", season)
                .query((resultSet, ignored) -> new StatusCount(
                        resultSet.getString("match_status"), resultSet.getInt("status_count")
                ))
                .list();
    }

    static List<ObservedCandidate> buildCandidates(List<CandidateRow> rows) {
        Map<Long, CandidateBuilder> builders = new LinkedHashMap<>();
        for (CandidateRow row : rows) {
            CandidateBuilder builder = builders.computeIfAbsent(row.runId(), ignored -> new CandidateBuilder(row));
            if (row.characterName() != null) {
                builder.roster.add(new PlayerIdentity(row.region(), row.realm(), row.characterName()));
            }
        }
        return builders.values().stream().map(CandidateBuilder::build).toList();
    }

    private static CandidateRow mapCandidateRow(ResultSet resultSet, int ignored) throws SQLException {
        OffsetDateTime completedAt = resultSet.getObject("completed_at", OffsetDateTime.class);
        return new CandidateRow(
                resultSet.getLong("id"), resultSet.getString("season_key"),
                resultSet.getString("dungeon_name"), resultSet.getString("dungeon_short_name"),
                resultSet.getInt("mythic_level"), completedAt.toInstant(), resultSet.getLong("clear_time_ms"),
                resultSet.getString("region"), resultSet.getString("realm"),
                resultSet.getString("character_name")
        );
    }

    record CandidateRow(
            long runId,
            String season,
            String dungeonName,
            String dungeonShortName,
            int mythicLevel,
            Instant completedAt,
            long clearTimeMs,
            String region,
            String realm,
            String characterName
    ) {
    }

    public record Coverage(int observedRuns, int matchedRuns) {
    }

    public record StatusCount(String status, int count) {
    }

    private static final class CandidateBuilder {
        private final CandidateRow row;
        private final Set<PlayerIdentity> roster = new java.util.LinkedHashSet<>();

        private CandidateBuilder(CandidateRow row) {
            this.row = row;
        }

        private ObservedCandidate build() {
            return new ObservedCandidate(
                    row.runId(), row.season(), row.dungeonName(), row.dungeonShortName(),
                    row.mythicLevel(), row.completedAt(), row.clearTimeMs(), roster
            );
        }
    }
}
