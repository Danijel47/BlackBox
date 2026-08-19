package com.blackbox.wow.service;

import com.blackbox.wow.client.MPlusObservation;
import com.blackbox.wow.client.MPlusObservation.Member;
import com.blackbox.wow.client.MPlusObservation.Modifier;
import com.blackbox.wow.client.MPlusObservation.RunDetails;
import com.blackbox.wow.client.MPlusObservation.RunSummary;
import com.blackbox.wow.client.RaiderIoCollectionException.Category;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MPlusCollectionPersistenceService {

    private static final long COLLECTION_PERIOD_SECONDS = 30L * 60L;

    private final JdbcClient jdbc;
    private final MPlusProgressRepository progressRepository;
    private final MPlusDungeonVaultRepository dungeonVaultRepository;

    public MPlusCollectionPersistenceService(
            JdbcClient jdbc,
            MPlusProgressRepository progressRepository,
            MPlusDungeonVaultRepository dungeonVaultRepository
    ) {
        this.jdbc = jdbc;
        this.progressRepository = progressRepository;
        this.dungeonVaultRepository = dungeonVaultRepository;
    }

    @Transactional
    public List<PendingRun> saveObservation(
            TrackedPlayer player,
            MPlusObservation observation,
            Instant observedAt
    ) {
        saveScoreSnapshot(player, observation, observedAt);
        progressRepository.recordMilestones(player, observation, observedAt);
        dungeonVaultRepository.saveWeeklySnapshots(player, observation, observedAt);
        List<PendingRun> pendingRuns = new ArrayList<>();
        for (RunSummary run : observation.runs()) {
            StoredRun storedRun = saveRunSummary(observation.season(), run, observedAt);
            linkRunToProfile(storedRun.id(), player, observedAt);
            if ("PENDING".equals(storedRun.detailsStatus())) {
                pendingRuns.add(new PendingRun(storedRun.id(), observation.season(), run.raiderIoRunId()));
            }
        }
        recordSuccess(player, observation, observedAt);
        return List.copyOf(pendingRuns);
    }

    @Transactional
    public void saveRunDetails(long storedRunId, RunDetails details) {
        jdbc.sql("DELETE FROM mplus_observed_run_member WHERE run_id = :runId")
                .param("runId", storedRunId)
                .update();
        jdbc.sql("DELETE FROM mplus_observed_run_modifier WHERE run_id = :runId")
                .param("runId", storedRunId)
                .update();
        for (Member member : details.members()) {
            saveMember(storedRunId, member);
        }
        for (Modifier modifier : details.modifiers()) {
            jdbc.sql("""
                            INSERT INTO mplus_observed_run_modifier (
                                run_id, modifier_id, modifier_name, modifier_slug
                            ) VALUES (:runId, :modifierId, :modifierName, :modifierSlug)
                            """)
                    .param("runId", storedRunId)
                    .param("modifierId", modifier.id())
                    .param("modifierName", modifier.name())
                    .param("modifierSlug", modifier.slug())
                    .update();
        }
        jdbc.sql("UPDATE mplus_observed_run SET details_status = 'LOADED' WHERE id = :runId")
                .param("runId", storedRunId)
                .update();
    }

    @Transactional
    public void markRunDetailsUnavailable(long storedRunId) {
        jdbc.sql("UPDATE mplus_observed_run SET details_status = 'UNAVAILABLE' WHERE id = :runId")
                .param("runId", storedRunId)
                .update();
    }

    @Transactional
    public void recordFailure(TrackedPlayer player, Instant attemptedAt, Category category) {
        jdbc.sql("""
                        INSERT INTO mplus_collection_status (
                            profile_id, region, realm, character_name, last_attempt_at,
                            observed_run_count, last_error_category
                        ) VALUES (
                            :profileId, :region, :realm, :characterName, :attemptedAt, 0, :category
                        )
                        ON CONFLICT (profile_id) DO UPDATE SET
                            region = EXCLUDED.region,
                            realm = EXCLUDED.realm,
                            character_name = EXCLUDED.character_name,
                            last_attempt_at = EXCLUDED.last_attempt_at,
                            last_error_category = EXCLUDED.last_error_category
                        """)
                .param("profileId", player.profileId())
                .param("region", player.region())
                .param("realm", player.realm())
                .param("characterName", player.name())
                .param("attemptedAt", attemptedAt)
                .param("category", category.name())
                .update();
    }

    @Transactional(readOnly = true)
    public List<CollectionStatus> statuses() {
        return jdbc.sql("""
                        SELECT profile_id, season_key, region, realm, character_name,
                               last_attempt_at, last_success_at, raider_io_crawled_at,
                               observed_run_count, last_error_category
                        FROM mplus_collection_status
                        """)
                .query((resultSet, rowNumber) -> new CollectionStatus(
                        resultSet.getLong("profile_id"),
                        resultSet.getString("season_key"),
                        resultSet.getString("region"),
                        resultSet.getString("realm"),
                        resultSet.getString("character_name"),
                        toInstant(resultSet.getObject("last_attempt_at", OffsetDateTime.class)),
                        toInstant(resultSet.getObject("last_success_at", OffsetDateTime.class)),
                        toInstant(resultSet.getObject("raider_io_crawled_at", OffsetDateTime.class)),
                        resultSet.getInt("observed_run_count"),
                        resultSet.getString("last_error_category")
                ))
                .list();
    }

    private void saveScoreSnapshot(TrackedPlayer player, MPlusObservation observation, Instant observedAt) {
        long bucketEpoch = Math.floorDiv(observedAt.getEpochSecond(), COLLECTION_PERIOD_SECONDS)
                * COLLECTION_PERIOD_SECONDS;
        jdbc.sql("""
                        INSERT INTO mplus_score_snapshot (
                            profile_id, season_key, region, realm, character_name,
                            score_all, score_dps, score_healer, score_tank,
                            raider_io_crawled_at, captured_period, captured_at
                        ) VALUES (
                            :profileId, :season, :region, :realm, :characterName,
                            :scoreAll, :scoreDps, :scoreHealer, :scoreTank,
                            :crawledAt, :capturedPeriod, :capturedAt
                        )
                        ON CONFLICT (profile_id, season_key, captured_period) DO UPDATE SET
                            region = EXCLUDED.region,
                            realm = EXCLUDED.realm,
                            character_name = EXCLUDED.character_name,
                            score_all = EXCLUDED.score_all,
                            score_dps = EXCLUDED.score_dps,
                            score_healer = EXCLUDED.score_healer,
                            score_tank = EXCLUDED.score_tank,
                            raider_io_crawled_at = EXCLUDED.raider_io_crawled_at,
                            captured_at = EXCLUDED.captured_at
                        """)
                .param("profileId", player.profileId())
                .param("season", observation.season())
                .param("region", observation.region())
                .param("realm", observation.realm())
                .param("characterName", observation.name())
                .param("scoreAll", observation.scoreAll(), Types.NUMERIC)
                .param("scoreDps", observation.scoreDps(), Types.NUMERIC)
                .param("scoreHealer", observation.scoreHealer(), Types.NUMERIC)
                .param("scoreTank", observation.scoreTank(), Types.NUMERIC)
                .param("crawledAt", observation.crawledAt(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("capturedPeriod", Instant.ofEpochSecond(bucketEpoch))
                .param("capturedAt", observedAt)
                .update();
    }

    private StoredRun saveRunSummary(String season, RunSummary run, Instant observedAt) {
        return jdbc.sql("""
                        INSERT INTO mplus_observed_run (
                            season_key, raider_io_run_id, dungeon_name, dungeon_short_name,
                            map_challenge_mode_id, mythic_level, completed_at, clear_time_ms,
                            par_time_ms, num_keystone_upgrades, score, timed,
                            observed_recent, observed_best, observed_weekly,
                            first_observed_at, last_observed_at
                        ) VALUES (
                            :season, :runId, :dungeonName, :shortName,
                            :challengeModeId, :level, :completedAt, :clearTime,
                            :parTime, :upgrades, :score, :timed,
                            :recent, :best, :weekly, :observedAt, :observedAt
                        )
                        ON CONFLICT (season_key, raider_io_run_id) DO UPDATE SET
                            dungeon_name = EXCLUDED.dungeon_name,
                            dungeon_short_name = EXCLUDED.dungeon_short_name,
                            map_challenge_mode_id = EXCLUDED.map_challenge_mode_id,
                            mythic_level = EXCLUDED.mythic_level,
                            completed_at = EXCLUDED.completed_at,
                            clear_time_ms = EXCLUDED.clear_time_ms,
                            par_time_ms = EXCLUDED.par_time_ms,
                            num_keystone_upgrades = EXCLUDED.num_keystone_upgrades,
                            score = EXCLUDED.score,
                            timed = EXCLUDED.timed,
                            observed_recent = mplus_observed_run.observed_recent OR EXCLUDED.observed_recent,
                            observed_best = mplus_observed_run.observed_best OR EXCLUDED.observed_best,
                            observed_weekly = mplus_observed_run.observed_weekly OR EXCLUDED.observed_weekly,
                            last_observed_at = EXCLUDED.last_observed_at
                        RETURNING id, details_status
                        """)
                .param("season", season)
                .param("runId", run.raiderIoRunId())
                .param("dungeonName", nonBlank(run.dungeonName(), "Unknown dungeon"))
                .param("shortName", nonBlank(run.dungeonShortName(), "Unknown"))
                .param("challengeModeId", run.mapChallengeModeId(), Types.INTEGER)
                .param("level", run.mythicLevel())
                .param("completedAt", run.completedAt())
                .param("clearTime", run.clearTimeMs())
                .param("parTime", run.parTimeMs())
                .param("upgrades", run.keystoneUpgrades())
                .param("score", run.score(), Types.NUMERIC)
                .param("timed", run.parTimeMs() > 0 && run.clearTimeMs() <= run.parTimeMs())
                .param("recent", run.recent())
                .param("best", run.best())
                .param("weekly", run.weekly())
                .param("observedAt", observedAt)
                .query((resultSet, rowNumber) -> new StoredRun(
                        resultSet.getLong("id"),
                        resultSet.getString("details_status")
                ))
                .single();
    }

    private void linkRunToProfile(long runId, TrackedPlayer player, Instant observedAt) {
        jdbc.sql("""
                        INSERT INTO mplus_observed_run_profile (
                            run_id, profile_id, observed_region, observed_realm,
                            observed_character_name, first_observed_at, last_observed_at
                        ) VALUES (
                            :runId, :profileId, :region, :realm,
                            :characterName, :observedAt, :observedAt
                        )
                        ON CONFLICT (run_id, profile_id) DO UPDATE SET
                            observed_region = EXCLUDED.observed_region,
                            observed_realm = EXCLUDED.observed_realm,
                            observed_character_name = EXCLUDED.observed_character_name,
                            last_observed_at = EXCLUDED.last_observed_at
                        """)
                .param("runId", runId)
                .param("profileId", player.profileId())
                .param("region", player.region())
                .param("realm", player.realm())
                .param("characterName", player.name())
                .param("observedAt", observedAt)
                .update();
    }

    private void saveMember(long runId, Member member) {
        if (member.characterName().isBlank() || member.region().isBlank() || member.realm().isBlank()) {
            return;
        }
        Long matchedProfileId = resolveProfile(member);
        jdbc.sql("""
                        INSERT INTO mplus_observed_run_member (
                            run_id, region, realm, character_name, class_name,
                            spec_name, role, matched_profile_id
                        ) VALUES (
                            :runId, :region, :realm, :characterName, :className,
                            :specName, :role, :matchedProfileId
                        )
                        """)
                .param("runId", runId)
                .param("region", member.region())
                .param("realm", member.realm())
                .param("characterName", member.characterName())
                .param("className", member.className())
                .param("specName", member.specName())
                .param("role", member.role())
                .param("matchedProfileId", matchedProfileId, Types.BIGINT)
                .update();
    }

    private Long resolveProfile(Member member) {
        List<Long> profileIds = jdbc.sql("""
                        SELECT DISTINCT profile_id
                        FROM tracked_character
                        WHERE active = TRUE
                          AND LOWER(region) = LOWER(:region)
                          AND LOWER(realm) = LOWER(:realm)
                          AND LOWER(character_name) = LOWER(:characterName)
                        """)
                .param("region", member.region())
                .param("realm", member.realm())
                .param("characterName", member.characterName())
                .query(Long.class)
                .list();
        return profileIds.size() == 1 ? profileIds.getFirst() : null;
    }

    private void recordSuccess(TrackedPlayer player, MPlusObservation observation, Instant observedAt) {
        jdbc.sql("""
                        INSERT INTO mplus_collection_status (
                            profile_id, season_key, region, realm, character_name,
                            last_attempt_at, last_success_at, raider_io_crawled_at,
                            observed_run_count, last_error_category
                        ) VALUES (
                            :profileId, :season, :region, :realm, :characterName,
                            :observedAt, :observedAt, :crawledAt, :runCount, NULL
                        )
                        ON CONFLICT (profile_id) DO UPDATE SET
                            season_key = EXCLUDED.season_key,
                            region = EXCLUDED.region,
                            realm = EXCLUDED.realm,
                            character_name = EXCLUDED.character_name,
                            last_attempt_at = EXCLUDED.last_attempt_at,
                            last_success_at = EXCLUDED.last_success_at,
                            raider_io_crawled_at = EXCLUDED.raider_io_crawled_at,
                            observed_run_count = EXCLUDED.observed_run_count,
                            last_error_category = NULL
                        """)
                .param("profileId", player.profileId())
                .param("season", observation.season())
                .param("region", observation.region())
                .param("realm", observation.realm())
                .param("characterName", observation.name())
                .param("observedAt", observedAt)
                .param("crawledAt", observation.crawledAt(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("runCount", observation.runs().size())
                .update();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record StoredRun(long id, String detailsStatus) {
    }

    public record PendingRun(long storedRunId, String season, long raiderIoRunId) {
    }

    public record CollectionStatus(
            long profileId,
            String season,
            String region,
            String realm,
            String characterName,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            Instant raiderIoCrawledAt,
            int observedRunCount,
            String lastErrorCategory
    ) {
    }
}
