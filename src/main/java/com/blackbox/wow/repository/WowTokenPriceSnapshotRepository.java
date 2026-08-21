package com.blackbox.wow.repository;

import com.blackbox.wow.entity.WowTokenPriceSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WowTokenPriceSnapshotRepository extends JpaRepository<WowTokenPriceSnapshotEntity, Long> {

    boolean existsByRegionAndCapturedAt(String region, Instant capturedAt);

    Optional<WowTokenPriceSnapshotEntity>
    findFirstByRegionAndCapturedAtGreaterThanEqualOrderByPriceCopperAscCapturedAtAsc(
            String region,
            Instant capturedAt
    );

    Optional<WowTokenPriceSnapshotEntity>
    findFirstByRegionAndCapturedAtGreaterThanEqualOrderByPriceCopperDescCapturedAtAsc(
            String region,
            Instant capturedAt
    );

    List<WowTokenPriceSnapshotEntity> findByRegionAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc(
            String region,
            Instant capturedAt
    );
}
