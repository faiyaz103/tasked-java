package com.tasked.modular.task.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbound view of a checklist item.
 *
 * <p>A dedicated record rather than the entity: serialising {@code TodoEntity} would follow its
 * {@code task} reference straight back into {@code TaskEntity} and recurse.
 */
public record TodoResponse(
        UUID id,
        String name,
        boolean done,
        LocalDateTime createdAt) {
}
