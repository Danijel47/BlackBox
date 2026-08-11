package com.example.blackbox.wow.repository;

import com.example.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarcraftLogProfileSnapshotRepository
        extends JpaRepository<WarcraftLogProfileSnapshotEntity, Long> {
    List<WarcraftLogProfileSnapshotEntity> findBySeasonKey(String seasonKey);
    Optional<WarcraftLogProfileSnapshotEntity> findBySeasonKeyAndProfileId(String seasonKey, long profileId);
}
