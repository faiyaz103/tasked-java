package com.tasked.modular.task.entities;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single checklist item belonging to a {@link TaskEntity}.
 *
 * <p>Unlike the cross-module {@code owner_id} on {@code TaskEntity}, this is a real JPA
 * association: task and todo live in the same module, ship together and are one aggregate, so
 * there is nothing to decouple. A todo has no independent lifecycle — it is created, loaded and
 * deleted through its parent.
 *
 * <p>Ownership is <em>derived</em>: a todo has no {@code owner_id} column of its own, and the
 * caller's right to touch it is established by joining to the parent's owner. See
 * {@code TodoRepo#findByIdAndTask_OwnerId}.
 */
@Entity
@Table(
    name = "todos",
    indexes = {
        // Every query against this table is "the todos of this task"; the FK constraint alone
        // does not create an index.
        @Index(name = "idx_todo_task_id", columnList = "task_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TodoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Named {@code done} rather than {@code isDone} on purpose: Lombok generates
     * {@code isDone()} either way and Jackson would serialise both as {@code "done"}, so the
     * shorter name is the one that matches its own accessor. The database column keeps the
     * {@code is_done} spelling.
     */
    @Column(name = "is_done", nullable = false)
    @Builder.Default
    private boolean done = false;

    /**
     * The owning side of the relationship — this is the entity holding the FK column, and
     * therefore the side Hibernate reads when deciding what to write.
     *
     * <p>{@code LAZY} is explicit because {@code @ManyToOne} defaults to {@code EAGER}, which
     * would fire one extra {@code SELECT} per todo just to fetch a parent the caller already
     * has in hand.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_todo_task"))
    private TaskEntity task;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
