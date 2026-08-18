package com.blackbox.time_to_go.repository;

import com.blackbox.time_to_go.entity.TimeToGoSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimeToGoSnapshotRepository extends JpaRepository<TimeToGoSnapshotEntity, Long> {

    List<TimeToGoSnapshotEntity> findByRouteKey(String routeKey);

    Optional<TimeToGoSnapshotEntity> findFirstByRouteKeyOrderBySampledAtDesc(String routeKey);
}
