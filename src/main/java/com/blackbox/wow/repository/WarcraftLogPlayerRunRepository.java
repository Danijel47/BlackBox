package com.blackbox.wow.repository;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarcraftLogPlayerRunRepository extends JpaRepository<WarcraftLogPlayerRunEntity, Long> {
    List<WarcraftLogPlayerRunEntity> findBySeasonKey(String seasonKey);

    @Query(value = """
            SELECT log_run.*
            FROM warcraft_log_player_run log_run
            JOIN mplus_run_log_match run_match
              ON run_match.season_key = log_run.season_key
             AND run_match.report_code = log_run.report_code
             AND run_match.fight_id = log_run.fight_id
             AND run_match.match_status = 'MATCHED'
            JOIN mplus_observed_run observed_run
              ON observed_run.id = run_match.mplus_run_id
            WHERE log_run.season_key = :seasonKey
              AND log_run.keystone_level >= :minimumKeystoneLevel
              AND observed_run.timed = TRUE
            """, nativeQuery = true)
    List<WarcraftLogPlayerRunEntity> findTimedBySeasonKeyAndMinimumKeystoneLevel(
            @Param("seasonKey") String seasonKey,
            @Param("minimumKeystoneLevel") int minimumKeystoneLevel
    );
}
