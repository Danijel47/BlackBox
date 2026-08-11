package com.example.blackbox.wow.repository;

import com.example.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarcraftLogPlayerRunRepository extends JpaRepository<WarcraftLogPlayerRunEntity, Long> {
    List<WarcraftLogPlayerRunEntity> findBySeasonKey(String seasonKey);
}
