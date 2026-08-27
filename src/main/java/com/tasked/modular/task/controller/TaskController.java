package com.tasked.modular.task.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tasked.modular.shared.auth.CurrentUserId;
import com.tasked.modular.shared.auth.Policies;
import com.tasked.modular.shared.dtos.PageResponse;
import com.tasked.modular.task.dtos.CreateTaskDto;
import com.tasked.modular.task.dtos.TaskResponse;
import com.tasked.modular.task.dtos.TodoResponse;
import com.tasked.modular.task.dtos.UpdateTaskDto;
import com.tasked.modular.task.dtos.UpdateTaskStatusDto;
import com.tasked.modular.task.dtos.UpdateTodoDoneDto;
import com.tasked.modular.task.service.TaskService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    // create a task
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Policies.USER)
    public TaskResponse createTask(@CurrentUserId UUID userId,
                                   @Valid @RequestBody CreateTaskDto dto) {
        return taskService.create(userId, dto);
    }

    // get list of all tasks of an user
    @GetMapping
    @PreAuthorize(Policies.USER)
    public PageResponse<TaskResponse> listTasks(
            @CurrentUserId UUID userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return taskService.list(userId, pageable);
    }

    // get a task by id
    @GetMapping("{taskId}")
    @PreAuthorize(Policies.USER)
    public TaskResponse getTask(@CurrentUserId UUID userId, @PathVariable UUID taskId) {
        return taskService.getById(taskId, userId);
    }

    // update task
    @PutMapping("{taskId}")
    @PreAuthorize(Policies.USER)
    public TaskResponse updateTask(@CurrentUserId UUID userId,
                                   @PathVariable UUID taskId,
                                   @Valid @RequestBody UpdateTaskDto dto) {
        return taskService.update(taskId, userId, dto);
    }

    // change task status
    @PatchMapping("{taskId}/status")
    @PreAuthorize(Policies.USER)
    public TaskResponse updateTaskStatus(@CurrentUserId UUID userId,
                                         @PathVariable UUID taskId,
                                         @Valid @RequestBody UpdateTaskStatusDto dto) {
        return taskService.updateStatus(taskId, userId, dto);
    }

    // todos status
    @PatchMapping("{taskId}/todos/{todoId}/done")
    @PreAuthorize(Policies.USER)
    public TodoResponse updateTodoDone(@CurrentUserId UUID userId,
                                       @PathVariable UUID taskId,
                                       @PathVariable UUID todoId,
                                       @Valid @RequestBody UpdateTodoDoneDto dto) {
        return taskService.updateTodoDone(taskId, todoId, userId, dto);
    }

    // delete tasks
    @DeleteMapping("{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Policies.USER)
    public void deleteTask(@CurrentUserId UUID userId, @PathVariable UUID taskId) {
        taskService.delete(taskId, userId);
    }
}
