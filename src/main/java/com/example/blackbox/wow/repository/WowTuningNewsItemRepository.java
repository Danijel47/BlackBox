package com.example.blackbox.wow.repository;

import com.example.blackbox.wow.entity.WowTuningNewsItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WowTuningNewsItemRepository extends JpaRepository<WowTuningNewsItemEntity, String> {
}
