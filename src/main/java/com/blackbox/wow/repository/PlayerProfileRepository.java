package com.blackbox.wow.repository;

import com.blackbox.wow.entity.PlayerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfileEntity, Long> {

    List<PlayerProfileEntity> findByActiveTrueAndSeasonRecapEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<PlayerProfileEntity> findByActiveTrueAndVaultWatchEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<PlayerProfileEntity> findByActiveTrueAndTitleWatchEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<PlayerProfileEntity> findByActiveTrueOrderByDisplayOrderAscIdAsc();

    List<PlayerProfileEntity>
    findByActiveTrueAndTitleZeroPointOneWatchEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<PlayerProfileEntity> findAllByOrderByDisplayOrderAscIdAsc();

    Optional<PlayerProfileEntity> findByProfileNameIgnoreCase(String profileName);

    @Query("select coalesce(max(profile.displayOrder), 0) from PlayerProfileEntity profile")
    int findMaximumDisplayOrder();
}
