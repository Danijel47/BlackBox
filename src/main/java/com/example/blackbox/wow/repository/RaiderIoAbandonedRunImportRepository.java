package com.example.blackbox.wow.repository;

import com.example.blackbox.wow.entity.RaiderIoAbandonedRunImportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface RaiderIoAbandonedRunImportRepository
        extends JpaRepository<RaiderIoAbandonedRunImportEntity, Long> {

    Optional<RaiderIoAbandonedRunImportEntity>
    findByRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCaseAndSeasonSlugAndGeneratedAt(
            String region,
            String realm,
            String characterName,
            String seasonSlug,
            Instant generatedAt
    );

    Optional<RaiderIoAbandonedRunImportEntity>
    findFirstByRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCaseAndSeasonSlugOrderByGeneratedAtDesc(
            String region,
            String realm,
            String characterName,
            String seasonSlug
    );
}
