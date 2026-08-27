package com.tasked.modular.task.dtos;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create contract: a title plus the checklist it starts with.
 *
 * <p>Neither {@code status} nor {@code ownerId} appears here. Status is always {@code TODO} at
 * creation and has its own endpoint afterwards; the owner comes from the token, and accepting a
 * client-supplied one would be an authorization bypass.
 *
 * <p>{@code @Valid} on the list <em>component</em> is what makes Bean Validation descend into
 * each element — the annotation on the enclosing parameter alone stops at this record.
 */
public record CreateTaskDto(

        @NotBlank(message = "Title cannot be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        String title,

        @Size(max = 100, message = "A task can have at most 100 todos")
        List<@Valid CreateTodoDto> todos) {

    /** Todos are optional; an omitted list means a task with an empty checklist. */
    public List<CreateTodoDto> todosOrEmpty() {
        return todos == null ? List.of() : todos;
    }
}
