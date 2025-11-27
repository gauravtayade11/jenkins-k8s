package com.example.demo.service;

import com.example.demo.model.Task;
import com.example.demo.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService Unit Tests")
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskService taskService;

    private Task task;

    @BeforeEach
    void setUp() {
        task = new Task("Test Task", "Test Description");
        task.setId(1L);
    }

    @Test
    @DisplayName("Should return all tasks")
    void getAllTasks_ShouldReturnAllTasks() {
        // Given
        List<Task> expectedTasks = Arrays.asList(
                new Task("Task 1"),
                new Task("Task 2")
        );
        when(taskRepository.findAll()).thenReturn(expectedTasks);

        // When
        List<Task> actualTasks = taskService.getAllTasks();

        // Then
        assertEquals(2, actualTasks.size());
        verify(taskRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("Should return task when found by ID")
    void getTaskById_WhenExists_ShouldReturnTask() {
        // Given
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        // When
        Optional<Task> result = taskService.getTaskById(1L);

        // Then
        assertTrue(result.isPresent());
        assertEquals("Test Task", result.get().getTitle());
    }

    @Test
    @DisplayName("Should return empty when task not found")
    void getTaskById_WhenNotExists_ShouldReturnEmpty() {
        // Given
        when(taskRepository.findById(999L)).thenReturn(Optional.empty());

        // When
        Optional<Task> result = taskService.getTaskById(999L);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should create task with timestamps")
    void createTask_ShouldSetTimestampsAndSave() {
        // Given
        Task newTask = new Task("New Task");
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task savedTask = invocation.getArgument(0);
            savedTask.setId(1L);
            return savedTask;
        });

        // When
        Task result = taskService.createTask(newTask);

        // Then
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        verify(taskRepository, times(1)).save(newTask);
    }

    @Test
    @DisplayName("Should update existing task")
    void updateTask_WhenExists_ShouldUpdate() {
        // Given
        Task updatedTask = new Task("Updated Title", "Updated Description");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenReturn(task);

        // When
        Optional<Task> result = taskService.updateTask(1L, updatedTask);

        // Then
        assertTrue(result.isPresent());
        assertEquals("Updated Title", result.get().getTitle());
        assertEquals("Updated Description", result.get().getDescription());
    }

    @Test
    @DisplayName("Should return empty when updating non-existent task")
    void updateTask_WhenNotExists_ShouldReturnEmpty() {
        // Given
        when(taskRepository.findById(999L)).thenReturn(Optional.empty());

        // When
        Optional<Task> result = taskService.updateTask(999L, new Task());

        // Then
        assertFalse(result.isPresent());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should delete existing task")
    void deleteTask_WhenExists_ShouldReturnTrue() {
        // Given
        when(taskRepository.deleteById(1L)).thenReturn(true);

        // When
        boolean result = taskService.deleteTask(1L);

        // Then
        assertTrue(result);
        verify(taskRepository, times(1)).deleteById(1L);
    }

    @Test
    @DisplayName("Should return false when deleting non-existent task")
    void deleteTask_WhenNotExists_ShouldReturnFalse() {
        // Given
        when(taskRepository.deleteById(999L)).thenReturn(false);

        // When
        boolean result = taskService.deleteTask(999L);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("Should mark task as completed")
    void completeTask_WhenExists_ShouldMarkCompleted() {
        // Given
        task.setCompleted(false);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenReturn(task);

        // When
        Optional<Task> result = taskService.completeTask(1L);

        // Then
        assertTrue(result.isPresent());
        assertTrue(result.get().isCompleted());
    }

    @Test
    @DisplayName("Should return task count")
    void getTaskCount_ShouldReturnCount() {
        // Given
        when(taskRepository.count()).thenReturn(5L);

        // When
        long count = taskService.getTaskCount();

        // Then
        assertEquals(5L, count);
    }
}
