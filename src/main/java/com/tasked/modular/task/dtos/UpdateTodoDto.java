package com.tasked.modular.task.dtos;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One checklist item inside an update-task request.
 *
 * <p>The {@code id} decides what happens to it: present means "update this existing todo",
 * absent means "add a new one". A todo the client omits from the list altogether is deleted.
 *
 * <p>There is deliberately no {@code done} component. Toggling an item is a separate,
 * single-purpose endpoint, so re-submitting a checklist can never silently tick or untick
 * boxes as a side effect of renaming something.
 */
public record UpdateTodoDto(

        /** {@code null} for a new item; otherwise must already belong to the task being updated. */
        UUID id,

        @NotBlank(message = "Todo name cannot be blank")
        @Size(max = 200, message = "Todo name must be at most 200 characters")
        String name) {
}
