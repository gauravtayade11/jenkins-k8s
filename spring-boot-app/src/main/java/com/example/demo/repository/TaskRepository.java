package com.example.demo.repository;

import com.example.demo.model.Task;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory repository for Task entities.
 * In a real application, this would be replaced with a JPA repository.
 */
@Repository
public class TaskRepository {

    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    /**
     * Find all tasks.
     *
     * @return list of all tasks
     */
    public List<Task> findAll() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * Find a task by its ID.
     *
     * @param id the task ID
     * @return Optional containing the task if found
     */
    public Optional<Task> findById(Long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /**
     * Save a task (create or update).
     *
     * @param task the task to save
     * @return the saved task with ID assigned
     */
    public Task save(Task task) {
        if (task.getId() == null) {
            task.setId(idGenerator.getAndIncrement());
        }
        tasks.put(task.getId(), task);
        return task;
    }

    /**
     * Delete a task by its ID.
     *
     * @param id the task ID
     * @return true if the task was deleted, false if not found
     */
    public boolean deleteById(Long id) {
        return tasks.remove(id) != null;
    }

    /**
     * Check if a task exists by ID.
     *
     * @param id the task ID
     * @return true if exists
     */
    public boolean existsById(Long id) {
        return tasks.containsKey(id);
    }

    /**
     * Count total tasks.
     *
     * @return the count of tasks
     */
    public long count() {
        return tasks.size();
    }

    /**
     * Delete all tasks.
     */
    public void deleteAll() {
        tasks.clear();
    }
}
