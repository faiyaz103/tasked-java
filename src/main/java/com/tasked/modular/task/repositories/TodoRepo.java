package com.tasked.modular.task.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tasked.modular.task.entities.TodoEntity;

public interface TodoRepo extends JpaRepository<TodoEntity, UUID> {

    /**
     * Loads a todo only when it belongs to the given task <em>and</em> that task belongs to the
     * caller.
     *
     * <p>Todos carry no {@code owner_id} of their own — ownership is derived through the parent,
     * and the underscores make that traversal explicit ({@code task.id}, {@code task.ownerId}).
     * Expressing both conditions in SQL means a mismatched or foreign id returns empty rather
     * than relying on an {@code if} that a later edit could drop.
     */
    Optional<TodoEntity> findByIdAndTask_IdAndTask_OwnerId(UUID id, UUID taskId, UUID ownerId);
}
