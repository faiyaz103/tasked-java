package com.tasked.modular.task.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One checklist item inside a create-task request.
 *
 * <p>Carries a name and nothing else: a brand-new todo is always undone, and its parent is the
 * task being created, so neither {@code done} nor a task id has any meaning a client could
 * legitimately supply.
 */
public record CreateTodoDto(

        @NotBlank(message = "Todo name cannot be blank")
        @Size(max = 200, message = "Todo name must be at most 200 characters")
        String name) {
}
