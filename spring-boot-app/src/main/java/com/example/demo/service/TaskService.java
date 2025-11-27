package com.example.demo.service;

import com.example.demo.model.Task;
import com.example.demo.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for Task operations.
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * Get all tasks.
     *
     * @return list of all tasks
     */
    public List<Task> getAllTasks() {
        return taskRepository.findAll();
    }

    /**
     * Get a task by ID.
     *
     * @param id the task ID
     * @return Optional containing the task if found
     */
    public Optional<Task> getTaskById(Long id) {
        return taskRepository.findById(id);
    }

    /**
     * Create a new task.
     *
     * @param task the task to create
     * @return the created task with generated ID
     */
    public Task createTask(Task task) {
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return taskRepository.save(task);
    }

    /**
     * Update an existing task.
     *
     * @param id   the task ID
     * @param task the updated task data
     * @return Optional containing the updated task if found
     */
    public Optional<Task> updateTask(Long id, Task task) {
        return taskRepository.findById(id)
                .map(existingTask -> {
                    existingTask.setTitle(task.getTitle());
                    existingTask.setDescription(task.getDescription());
                    existingTask.setCompleted(task.isCompleted());
                    existingTask.setUpdatedAt(LocalDateTime.now());
                    return taskRepository.save(existingTask);
                });
    }

    /**
     * Delete a task by ID.
     *
     * @param id the task ID
     * @return true if deleted, false if not found
     */
    public boolean deleteTask(Long id) {
        return taskRepository.deleteById(id);
    }

    /**
     * Mark a task as completed.
     *
     * @param id the task ID
     * @return Optional containing the updated task if found
     */
    public Optional<Task> completeTask(Long id) {
        return taskRepository.findById(id)
                .map(task -> {
                    task.setCompleted(true);
                    task.setUpdatedAt(LocalDateTime.now());
                    return taskRepository.save(task);
                });
    }

    /**
     * Get count of all tasks.
     *
     * @return total task count
     */
    public long getTaskCount() {
        return taskRepository.count();
    }
}
