package com.tasked.modular.task.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tasked.modular.task.entities.TaskEntity;
import java.util.UUID;


public interface TaksRepo extends JpaRepository<TaskEntity, UUID> {
    List<TaskEntity> findByOwnerId(UUID ownerId);
    Optional<TaskEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
