package com.tasked.modular.task.entities;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.tasked.modular.shared.enums.TaskStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "tasks",
    indexes = {
        @Index(name = "idx_task_owner_id", columnList = "owner_id"),
        @Index(name = "idx_task_status", columnList = "status"),
        // Optional: Composite index if you frequently query by both at the same time
        // @Index(name = "idx_task_owner_status", columnList = "owner_id, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /**
     * The inverse side of the task/todo relationship.
     *
     * <p>{@code mappedBy} names the field on {@link TodoEntity} that owns the foreign key.
     * Without it Hibernate would treat the two sides as unrelated and silently create a third
     * join table.
     *
     * <p>{@code CascadeType.ALL} + {@code orphanRemoval} make the pair a true composition:
     * saving a task persists its new todos, deleting a task deletes them, and dropping one out
     * of this list issues the {@code DELETE} rather than leaving a row with a null parent.
     */
    @OneToMany(mappedBy = "task",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<TodoEntity> todos = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Relationship helpers ────────────────────────────────────────────────────────────
    //
    // A bidirectional association has two sides that can disagree in memory, and Hibernate
    // only persists what the owning side says. Mutating the collection alone would leave
    // task_id null; these keep both sides consistent, so callers never touch getTodos()
    // directly to add or remove.

    public void addTodo(TodoEntity todo) {
        todos.add(todo);
        todo.setTask(this);
    }

    public void removeTodo(TodoEntity todo) {
        todos.remove(todo);
        todo.setTask(null);
    }
}
