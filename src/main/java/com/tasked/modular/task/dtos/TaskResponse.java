package com.tasked.modular.task.dtos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.tasked.modular.shared.enums.TaskStatus;

/**
 * Outbound view of a task and its checklist.
 *
 * <p>{@code ownerId} is not exposed: the only tasks a caller can ever retrieve are their own,
 * so the field would carry no information while advertising an internal identifier.
 */
public record TaskResponse(
        UUID id,
        String title,
        TaskStatus status,
        List<TodoResponse> todos,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
