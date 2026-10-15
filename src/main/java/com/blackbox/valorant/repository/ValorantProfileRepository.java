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
                profile.headshotRefreshFailed = false, profile.headshotRefreshWarning = null,
                profile.combatKills = :kills, profile.combatDeaths = :deaths, profile.combatAssists = :assists,
                profile.combatDamageDealt = :damageDealt, profile.combatDamageReceived = :damageReceived,
                profile.combatScore = :score, profile.combatRounds = :rounds,
                profile.combatWins = :wins, profile.combatLosses = :losses, profile.combatDraws = :draws
            where profile.id = :id and profile.active = true
            """)
    int saveHeadshotResult(@Param("id") Long id, @Param("matches") int matches,
                          @Param("head") long head, @Param("body") long body,
                          @Param("leg") long leg, @Param("at") Instant at,
                          @Param("kills") Long kills, @Param("deaths") Long deaths, @Param("assists") Long assists,
                          @Param("damageDealt") Long damageDealt, @Param("damageReceived") Long damageReceived,
                          @Param("score") Long score, @Param("rounds") Long rounds,
                          @Param("wins") Integer wins, @Param("losses") Integer losses, @Param("draws") Integer draws);

    @Transactional
    @Modifying
    @Query("""
            update ValorantProfileEntity profile
            set profile.headshotAttemptedAt = :at, profile.headshotRefreshFailed = true,
                profile.headshotRefreshWarning = :warning
            where profile.id = :id and profile.active = true
            """)
    int saveHeadshotFailure(@Param("id") Long id, @Param("at") Instant at, @Param("warning") String warning);
}
