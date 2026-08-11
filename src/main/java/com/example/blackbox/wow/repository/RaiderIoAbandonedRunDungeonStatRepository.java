package com.example.blackbox.wow.repository;

import com.example.blackbox.wow.entity.RaiderIoAbandonedRunDungeonStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RaiderIoAbandonedRunDungeonStatRepository
        extends JpaRepository<RaiderIoAbandonedRunDungeonStatEntity, Long> {

    List<RaiderIoAbandonedRunDungeonStatEntity> findByImportIdOrderByAbandonedRunsDesc(Long importId);
}
