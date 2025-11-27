# Part 5: Spring Boot Sample Project

In this part, we'll create a complete Spring Boot application to demonstrate our CI/CD pipeline. The project includes REST APIs, unit tests, integration tests, and a production-ready Dockerfile.

## Project Overview

We'll build a simple **Task Management API** with:
- RESTful endpoints for CRUD operations
- Unit tests with JUnit 5
- Integration tests
- Health check endpoints
- Metrics with Actuator
- Multi-stage Docker build

---

## Project Structure

```
spring-boot-app/
├── src/
│   ├── main/
│   │   ├── java/com/example/demo/
│   │   │   ├── DemoApplication.java
│   │   │   ├── controller/
│   │   │   │   └── TaskController.java
│   │   │   ├── model/
│   │   │   │   └── Task.java
│   │   │   ├── repository/
│   │   │   │   └── TaskRepository.java
│   │   │   └── service/
│   │   │       └── TaskService.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/example/demo/
│           ├── controller/
│           │   └── TaskControllerTest.java
│           └── service/
│               └── TaskServiceTest.java
├── pom.xml
├── Dockerfile
└── k8s/
    ├── deployment.yaml
    └── service.yaml
```

---

## 1. Maven Configuration

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>demo</name>
    <description>Demo Spring Boot Project for Jenkins K8s Pipeline</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Actuator for health checks -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

            <!-- JaCoCo for code coverage -->
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.11</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 2. Application Code

### Main Application

```java
// src/main/java/com/example/demo/DemoApplication.java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

### Task Model

```java
// src/main/java/com/example/demo/model/Task.java
package com.example.demo.model;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public class Task {
    private Long id;

    @NotBlank(message = "Title is required")
    private String title;

    private String description;
    private boolean completed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructors, getters, setters...
}
```

### Task Controller

```java
// src/main/java/com/example/demo/controller/TaskController.java
package com.example.demo.controller;

import com.example.demo.model.Task;
import com.example.demo.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<Task> getAllTasks() {
        return taskService.getAllTasks();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> getTaskById(@PathVariable Long id) {
        return taskService.getTaskById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Task> createTask(@Valid @RequestBody Task task) {
        Task created = taskService.createTask(task);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Task> updateTask(
            @PathVariable Long id,
            @Valid @RequestBody Task task) {
        return taskService.updateTask(id, task)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        if (taskService.deleteTask(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
```

---

## 3. Application Configuration

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: demo-app

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true

info:
  app:
    name: ${spring.application.name}
    version: '@project.version@'
    description: Demo Spring Boot Application
```

---

## 4. Unit Tests

```java
// src/test/java/com/example/demo/service/TaskServiceTest.java
package com.example.demo.service;

import com.example.demo.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService();
    }

    @Test
    void createTask_ShouldReturnTaskWithId() {
        Task task = new Task();
        task.setTitle("Test Task");

        Task created = taskService.createTask(task);

        assertNotNull(created.getId());
        assertEquals("Test Task", created.getTitle());
    }

    @Test
    void getAllTasks_ShouldReturnAllTasks() {
        taskService.createTask(createTask("Task 1"));
        taskService.createTask(createTask("Task 2"));

        assertEquals(2, taskService.getAllTasks().size());
    }

    private Task createTask(String title) {
        Task task = new Task();
        task.setTitle(title);
        return task;
    }
}
```

---

## 5. Dockerfile (Multi-stage Build)

```dockerfile
# Dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

# Security: Run as non-root user
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Change ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 6. Kubernetes Deployment

### Deployment

```yaml
# spring-boot-app/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: demo-app
  labels:
    app: demo-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: demo-app
  template:
    metadata:
      labels:
        app: demo-app
    spec:
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true
      containers:
        - name: demo-app
          image: demo-app:latest
          ports:
            - containerPort: 8080
          resources:
            requests:
              cpu: "100m"
              memory: "256Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
```

### Service

```yaml
# spring-boot-app/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: demo-app
  labels:
    app: demo-app
spec:
  type: ClusterIP
  ports:
    - port: 80
      targetPort: 8080
      protocol: TCP
  selector:
    app: demo-app
```

---

## 7. Building and Testing Locally

```bash
# Build the project
cd spring-boot-app
mvn clean package

# Run tests
mvn test

# Generate coverage report
mvn jacoco:report
# Report at: target/site/jacoco/index.html

# Build Docker image
docker build -t demo-app:latest .

# Run container
docker run -p 8080:8080 demo-app:latest

# Test endpoints
curl http://localhost:8080/api/tasks
curl http://localhost:8080/actuator/health
```

---

## Next Part

In **Part 6**, we'll create the complete Jenkins pipeline:
- Multi-branch pipeline
- Parallel stages
- Code quality gates
- Deployment strategies

[← Back to Part 4](../part-4-dynamic-agents/README.md) | [Continue to Part 6: Complete Pipeline & Best Practices →](../part-6-pipeline-best-practices/README.md)

---

*This is Part 5 of a 6-part series on deploying Jenkins on Kubernetes with security best practices.*
