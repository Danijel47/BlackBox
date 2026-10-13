package com.blackbox.valorant.repository;

import com.blackbox.valorant.entity.ValorantProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ValorantProfileRepository extends JpaRepository<ValorantProfileEntity, Long> {
    List<ValorantProfileEntity> findAllByOrderByIdAsc();
    List<ValorantProfileEntity> findByActiveTrueOrderByIdAsc();
    Optional<ValorantProfileEntity> findByProfileNameIgnoreCase(String profileName);

    @Transactional
    @Modifying
    @Query("""
            update ValorantProfileEntity profile
            set profile.headshotMatchCount = :matches, profile.headHits = :head,
                profile.bodyHits = :body, profile.legHits = :leg,
                profile.headshotUpdatedAt = :at, profile.headshotAttemptedAt = :at,
                profile.headshotRefreshFailed = false
            where profile.id = :id and profile.active = true
            """)
    int saveHeadshotResult(@Param("id") Long id, @Param("matches") int matches,
                          @Param("head") long head, @Param("body") long body,
                          @Param("leg") long leg, @Param("at") Instant at);

    @Transactional
    @Modifying
    @Query("""
            update ValorantProfileEntity profile
            set profile.headshotAttemptedAt = :at, profile.headshotRefreshFailed = true
            where profile.id = :id and profile.active = true
            """)
    int saveHeadshotFailure(@Param("id") Long id, @Param("at") Instant at);
}
