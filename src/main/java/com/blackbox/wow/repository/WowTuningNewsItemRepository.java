package com.blackbox.wow.repository;

import com.blackbox.wow.entity.WowTuningNewsItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WowTuningNewsItemRepository extends JpaRepository<WowTuningNewsItemEntity, String> {
}
