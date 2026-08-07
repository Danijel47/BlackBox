package com.example.blackbox.time_to_go.repository;

import com.example.blackbox.time_to_go.entity.TimeToGoImportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimeToGoImportJobRepository extends JpaRepository<TimeToGoImportJobEntity, Long> {

    Optional<TimeToGoImportJobEntity> findFirstByOrderByCreatedAtDesc();

    List<TimeToGoImportJobEntity> findByImportKeyOrderByCreatedAtAsc(String importKey);
}
