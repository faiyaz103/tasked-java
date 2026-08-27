package com.tasked.modular.task.dtos;

import com.tasked.modular.shared.enums.TaskStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Status transition, kept off the general update contract.
 *
 * <p>Typed as the enum rather than a {@code String}, so the set of reachable values is closed by
 * construction: anything outside {@code TODO} / {@code COMPLETED} fails deserialization and is
 * mapped to a 400 by {@code GlobalExceptionHandler} before the service ever runs.
 */
public record UpdateTaskStatusDto(

        @NotNull(message = "Status is required")
        TaskStatus status) {
}
