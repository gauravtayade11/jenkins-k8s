package com.example.demo.controller;

import com.example.demo.model.Task;
import com.example.demo.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
@DisplayName("TaskController Integration Tests")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    private Task task;

    @BeforeEach
    void setUp() {
        task = new Task("Test Task", "Test Description");
        task.setId(1L);
    }

    @Test
    @DisplayName("GET /api/tasks - Should return all tasks")
    void getAllTasks_ShouldReturnTasks() throws Exception {
        // Given
        when(taskService.getAllTasks()).thenReturn(Arrays.asList(task));

        // When & Then
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Test Task")));
    }

    @Test
    @DisplayName("GET /api/tasks/{id} - Should return task when found")
    void getTaskById_WhenExists_ShouldReturnTask() throws Exception {
        // Given
        when(taskService.getTaskById(1L)).thenReturn(Optional.of(task));

        // When & Then
        mockMvc.perform(get("/api/tasks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.title", is("Test Task")));
    }

    @Test
    @DisplayName("GET /api/tasks/{id} - Should return 404 when not found")
    void getTaskById_WhenNotExists_ShouldReturn404() throws Exception {
        // Given
        when(taskService.getTaskById(999L)).thenReturn(Optional.empty());

        // When & Then
        mockMvc.perform(get("/api/tasks/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/tasks - Should create task")
    void createTask_ShouldReturnCreatedTask() throws Exception {
        // Given
        Task newTask = new Task("New Task");
        newTask.setId(1L);
        when(taskService.createTask(any(Task.class))).thenReturn(newTask);

        // When & Then
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Task("New Task"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.title", is("New Task")));
    }

    @Test
    @DisplayName("POST /api/tasks - Should return 400 for invalid task")
    void createTask_WithInvalidData_ShouldReturn400() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Task())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/tasks/{id} - Should update task")
    void updateTask_WhenExists_ShouldReturnUpdatedTask() throws Exception {
        // Given
        Task updatedTask = new Task("Updated Task");
        updatedTask.setId(1L);
        when(taskService.updateTask(eq(1L), any(Task.class))).thenReturn(Optional.of(updatedTask));

        // When & Then
        mockMvc.perform(put("/api/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Task("Updated Task"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated Task")));
    }

    @Test
    @DisplayName("PUT /api/tasks/{id} - Should return 404 when not found")
    void updateTask_WhenNotExists_ShouldReturn404() throws Exception {
        // Given
        when(taskService.updateTask(eq(999L), any(Task.class))).thenReturn(Optional.empty());

        // When & Then
        mockMvc.perform(put("/api/tasks/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Task("Task"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/tasks/{id} - Should delete task")
    void deleteTask_WhenExists_ShouldReturn204() throws Exception {
        // Given
        when(taskService.deleteTask(1L)).thenReturn(true);

        // When & Then
        mockMvc.perform(delete("/api/tasks/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/tasks/{id} - Should return 404 when not found")
    void deleteTask_WhenNotExists_ShouldReturn404() throws Exception {
        // Given
        when(taskService.deleteTask(999L)).thenReturn(false);

        // When & Then
        mockMvc.perform(delete("/api/tasks/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/tasks/{id}/complete - Should complete task")
    void completeTask_WhenExists_ShouldReturnCompletedTask() throws Exception {
        // Given
        task.setCompleted(true);
        when(taskService.completeTask(1L)).thenReturn(Optional.of(task));

        // When & Then
        mockMvc.perform(patch("/api/tasks/1/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed", is(true)));
    }

    @Test
    @DisplayName("GET /api/tasks/stats - Should return statistics")
    void getStats_ShouldReturnTaskCount() throws Exception {
        // Given
        when(taskService.getTaskCount()).thenReturn(5L);

        // When & Then
        mockMvc.perform(get("/api/tasks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTasks", is(5)));
    }
}
