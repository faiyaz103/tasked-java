package com.tasked.modular.task.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.tasked.modular.task.entities.TaskEntity;

public interface TaskRepo extends JpaRepository<TaskEntity, UUID> {

    /**
     * One page of the caller's tasks, deliberately <strong>without</strong> the todo collection.
     *
     * <p>Adding {@code @EntityGraph} here would make Hibernate join the todos and then apply
     * {@code LIMIT}/{@code OFFSET} in memory (HHH000104), i.e. load every task the owner has
     * before discarding all but one page. The service pairs this with
     * {@link #findWithTodosByIdIn} to hydrate exactly the page it kept.
     */
    Page<TaskEntity> findByOwnerId(UUID ownerId, Pageable pageable);

    /**
     * Hydrates the todos of an already-selected set of tasks in a single query.
     *
     * <p>No {@code ownerId} parameter because the ids always come from a query that was already
     * scoped to the owner.
     */
    @EntityGraph(attributePaths = "todos")
    List<TaskEntity> findWithTodosByIdIn(Collection<UUID> ids);

    /**
     * A single task with its todos joined in.
     *
     * <p>Scoping by {@code ownerId} in the query rather than checking it afterwards is what
     * makes it impossible to forget: a task belonging to someone else simply does not come
     * back, and the service turns the empty result into a 404.
     */
    @EntityGraph(attributePaths = "todos")
    Optional<TaskEntity> findWithTodosByIdAndOwnerId(UUID id, UUID ownerId);

    /** Ownership-scoped lookup for the paths that do not need the collection. */
    Optional<TaskEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
