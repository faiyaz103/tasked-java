package com.tasked.modular.task.dtos;

import jakarta.validation.constraints.NotNull;

/**
 * Ticks or unticks a single checklist item.
 *
 * <p>Boxed {@code Boolean} rather than {@code boolean}: a primitive would silently default to
 * {@code false} when the field is missing from the body, turning a malformed request into an
 * unticked box. The wrapper lets {@code @NotNull} reject it as a 400 instead.
 */
public record UpdateTodoDoneDto(

        @NotNull(message = "done is required")
        Boolean done) {
}
