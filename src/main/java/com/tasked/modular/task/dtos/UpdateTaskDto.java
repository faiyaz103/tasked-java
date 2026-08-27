package com.tasked.modular.task.dtos;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Update contract: the title and the full checklist, as the client believes it should be.
 *
 * <p>This is a <strong>replace</strong>, not a patch — items missing from {@code todos} are
 * deleted. That is why the whole operation runs in one transaction: a half-applied checklist
 * would leave the task in a state the client never asked for.
 *
 * <p>{@code status} and {@code done} are excluded on purpose. Both are progress signals with
 * their own endpoints; letting them ride along in a general edit would mean an ordinary rename
 * could reopen a completed task.
 */
public record UpdateTaskDto(

        @NotBlank(message = "Title cannot be blank")
        @Size(max = 200, message = "Title must be at most 200 characters")
        String title,

        @Size(max = 100, message = "A task can have at most 100 todos")
        List<@Valid UpdateTodoDto> todos) {

    /** An omitted list clears the checklist, which is the honest reading of a replace. */
    public List<UpdateTodoDto> todosOrEmpty() {
        return todos == null ? List.of() : todos;
    }
}
