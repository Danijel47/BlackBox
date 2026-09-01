package com.blackbox.wow.repository;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarcraftLogPlayerRunRepository extends JpaRepository<WarcraftLogPlayerRunEntity, Long> {
    List<WarcraftLogPlayerRunEntity> findBySeasonKey(String seasonKey);

    @Query("""
            SELECT run
            FROM WarcraftLogPlayerRunEntity run
            WHERE run.seasonKey = :seasonKey
              AND run.keystoneLevel >= :minimumKeystoneLevel
              AND run.timed = true
            """)
    List<WarcraftLogPlayerRunEntity> findTimedBySeasonKeyAndMinimumKeystoneLevel(
            @Param("seasonKey") String seasonKey,
            @Param("minimumKeystoneLevel") int minimumKeystoneLevel
    );
}
