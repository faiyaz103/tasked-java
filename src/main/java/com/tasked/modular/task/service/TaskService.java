package com.tasked.modular.task.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tasked.modular.shared.dtos.PageResponse;
import com.tasked.modular.shared.exception.NotFoundException;
import com.tasked.modular.task.dtos.CreateTaskDto;
import com.tasked.modular.task.dtos.CreateTodoDto;
import com.tasked.modular.task.dtos.TaskResponse;
import com.tasked.modular.task.dtos.TodoResponse;
import com.tasked.modular.task.dtos.UpdateTaskDto;
import com.tasked.modular.task.dtos.UpdateTaskStatusDto;
import com.tasked.modular.task.dtos.UpdateTodoDoneDto;
import com.tasked.modular.task.dtos.UpdateTodoDto;
import com.tasked.modular.task.entities.TaskEntity;
import com.tasked.modular.task.entities.TodoEntity;
import com.tasked.modular.task.repositories.TaskRepo;
import com.tasked.modular.task.repositories.TodoRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Business logic for tasks and their checklists.
 *
 * <p>Two rules run through every method here:
 *
 * <ol>
 *   <li><strong>Ownership is a query condition, never an {@code if}.</strong> Each lookup is
 *       scoped by the caller's id, so a task belonging to someone else simply does not come
 *       back and turns into a 404 — not a 403, which would confirm the row exists.</li>
 *   <li><strong>Entities are mapped to DTOs inside the transaction.</strong> {@code
 *       open-in-view} is disabled, so a lazy collection touched after the method returns would
 *       raise {@code LazyInitializationException}.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepo taskRepo;
    private final TodoRepo todoRepo;

    // ── Create ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a task together with its initial checklist, in one transaction.
     *
     * <p>The todos are never saved through {@code TodoRepo}: {@code CascadeType.ALL} on the
     * association persists them with the parent, so the task and every item it was created with
     * either all exist or none do.
     */
    @Transactional
    public TaskResponse create(UUID ownerId, CreateTaskDto dto) {
        TaskEntity task = TaskEntity.builder()
                .title(dto.title())
                .ownerId(ownerId)
                .build();

        for (CreateTodoDto item : dto.todosOrEmpty()) {
            task.addTodo(TodoEntity.builder().name(item.name()).build());
        }

        taskRepo.save(task);
        log.info("User {} created task {} with {} todos", ownerId, task.getId(), task.getTodos().size());
        return toResponse(task);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────

    /** A single task with its checklist. */
    @Transactional(readOnly = true)
    public TaskResponse getById(UUID taskId, UUID ownerId) {
        return toResponse(loadOwnedWithTodos(taskId, ownerId));
    }

    /**
     * One page of the caller's tasks, checklists included, in exactly two queries.
     *
     * <p>The page is selected first without the collection, then the todos of just those tasks
     * are fetched in a single join. Doing it in one step instead — an {@code @EntityGraph} on a
     * pageable query — makes Hibernate paginate in memory after loading every matching row, and
     * mapping each task's todos lazily instead would be an N+1.
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(UUID ownerId, Pageable pageable) {
        Page<TaskEntity> page = taskRepo.findByOwnerId(ownerId, pageable);
        if (page.isEmpty()) {
            return PageResponse.of(page, List.of());
        }

        List<UUID> ids = page.getContent().stream().map(TaskEntity::getId).toList();
        Map<UUID, TaskEntity> hydrated = taskRepo.findWithTodosByIdIn(ids).stream()
                .collect(Collectors.toMap(TaskEntity::getId, Function.identity()));

        // Iterate the page, not the hydrated map: the page carries the requested sort order.
        List<TaskResponse> content = page.getContent().stream()
                .map(task -> toResponse(hydrated.getOrDefault(task.getId(), task)))
                .toList();

        return PageResponse.of(page, content);
    }

    // ── Update ──────────────────────────────────────────────────────────────────────────

    /**
     * Replaces the title and the checklist in a single transaction.
     *
     * <p>The checklist is reconciled by id: an item with an id is renamed, an item without one
     * is added, and an existing item the client omitted is deleted by {@code orphanRemoval}.
     * An id that does not belong to this task is a 404 rather than a silent insert — otherwise
     * a typo would quietly duplicate an item, and a probe with a foreign id would tell the
     * caller whether it exists.
     *
     * <p>Neither {@code status} nor any {@code done} flag is touched here. Renaming an item
     * preserves whether it was ticked.
     */
    @Transactional
    public TaskResponse update(UUID taskId, UUID ownerId, UpdateTaskDto dto) {
        TaskEntity task = loadOwnedWithTodos(taskId, ownerId);
        task.setTitle(dto.title());

        Map<UUID, TodoEntity> existing = task.getTodos().stream()
                .collect(Collectors.toMap(TodoEntity::getId, Function.identity()));

        Set<UUID> retained = new HashSet<>();
        for (UpdateTodoDto item : dto.todosOrEmpty()) {
            if (item.id() == null) {
                task.addTodo(TodoEntity.builder().name(item.name()).build());
                continue;
            }

            TodoEntity todo = existing.get(item.id());
            if (todo == null) {
                throw new NotFoundException("Todo " + item.id() + " does not belong to this task");
            }
            todo.setName(item.name());   // `done` deliberately left as it was
            retained.add(item.id());
        }

        // Collected first: removing while streaming the same list would fail mid-iteration.
        List<TodoEntity> removed = task.getTodos().stream()
                .filter(todo -> todo.getId() != null && !retained.contains(todo.getId()))
                .toList();
        removed.forEach(task::removeTodo);

        // Flush before mapping. Hibernate would otherwise defer the inserts to commit, i.e.
        // after this method returns, and a todo added above would be serialised with a null id
        // and a null createdAt. Flushing inside the transaction populates both.
        taskRepo.flush();

        log.info("User {} updated task {} ({} todos removed)", ownerId, taskId, removed.size());
        return toResponse(task);
    }

    /** Status transition, isolated from the general edit so progress cannot change by accident. */
    @Transactional
    public TaskResponse updateStatus(UUID taskId, UUID ownerId, UpdateTaskStatusDto dto) {
        TaskEntity task = loadOwnedWithTodos(taskId, ownerId);
        task.setStatus(dto.status());

        // So the returned updatedAt reflects this change rather than the previous one.
        taskRepo.flush();

        log.info("User {} set task {} status to {}", ownerId, taskId, dto.status());
        return toResponse(task);
    }

    /**
     * Ticks or unticks one checklist item.
     *
     * <p>Both the parent task and the owner are part of the lookup, so a todo id from another
     * task — or another user — never resolves.
     */
    @Transactional
    public TodoResponse updateTodoDone(UUID taskId, UUID todoId, UUID ownerId, UpdateTodoDoneDto dto) {
        TodoEntity todo = todoRepo.findByIdAndTask_IdAndTask_OwnerId(todoId, taskId, ownerId)
                .orElseThrow(() -> new NotFoundException("Todo not found"));

        todo.setDone(dto.done());
        return toTodoResponse(todo);
    }

    // ── Delete ──────────────────────────────────────────────────────────────────────────

    /** Deletes the task; the cascade removes its todos, so no orphans are left behind. */
    @Transactional
    public void delete(UUID taskId, UUID ownerId) {
        TaskEntity task = taskRepo.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new NotFoundException("Task not found"));

        taskRepo.delete(task);
        log.info("User {} deleted task {}", ownerId, taskId);
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────

    private TaskEntity loadOwnedWithTodos(UUID taskId, UUID ownerId) {
        return taskRepo.findWithTodosByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new NotFoundException("Task not found"));
    }

    private static TaskResponse toResponse(TaskEntity task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getStatus(),
                task.getTodos().stream().map(TaskService::toTodoResponse).toList(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private static TodoResponse toTodoResponse(TodoEntity todo) {
        return new TodoResponse(todo.getId(), todo.getName(), todo.isDone(), todo.getCreatedAt());
    }
}
