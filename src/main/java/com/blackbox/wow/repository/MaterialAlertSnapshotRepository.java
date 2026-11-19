package com.blackbox.wow.repository;

import com.blackbox.wow.entity.MaterialAlertSnapshotEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MaterialAlertSnapshotRepository extends JpaRepository<MaterialAlertSnapshotEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MaterialAlertSnapshotEntity s where s.id = 1")
    MaterialAlertSnapshotEntity lockSnapshot();
}
