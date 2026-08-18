package com.blackbox.wow.repository;

import com.blackbox.wow.entity.TrackedCharacterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrackedCharacterRepository extends JpaRepository<TrackedCharacterEntity, Long> {

    Optional<TrackedCharacterEntity> findByProfileIdAndSelectedTrueAndActiveTrue(Long profileId);

    List<TrackedCharacterEntity> findByProfileIdOrderBySelectedDescIdAsc(Long profileId);

    Optional<TrackedCharacterEntity>
    findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
            Long profileId,
            String region,
            String realm,
            String characterName
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TrackedCharacterEntity character
            set character.selected = false
            where character.profile.id = :profileId and character.selected = true
            """)
    void clearSelectedCharacter(@Param("profileId") Long profileId);
}
