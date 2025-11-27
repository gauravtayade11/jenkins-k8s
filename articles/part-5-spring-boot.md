# Jenkins on Kubernetes: Building a Real Spring Boot Application (Part 5)

*From code to container — a complete CI/CD example*

---

Theory is great. Working code is better.

In this article, we'll build a complete Spring Boot application that demonstrates everything we've set up. Not a "Hello World," but a proper REST API with tests, health checks, and production-ready Docker packaging.

By the end, you'll have:
- A Task Management REST API
- Unit and integration tests
- Multi-stage Dockerfile
- Kubernetes deployment manifests

Let's build something real.

---

## What We're Building

A simple but complete Task Management API:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/tasks` | GET | List all tasks |
| `/api/tasks/{id}` | GET | Get a task |
| `/api/tasks` | POST | Create a task |
| `/api/tasks/{id}` | PUT | Update a task |
| `/api/tasks/{id}` | DELETE | Delete a task |
| `/actuator/health` | GET | Health check |

Why this example? It's simple enough to understand quickly, complex enough to demonstrate real patterns.

---

## Project Structure

```
spring-boot-app/
├── src/
│   ├── main/
│   │   ├── java/com/example/demo/
│   │   │   ├── DemoApplication.java
│   │   │   ├── controller/TaskController.java
│   │   │   ├── model/Task.java
│   │   │   ├── repository/TaskRepository.java
│   │   │   └── service/TaskService.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/example/demo/
│           ├── controller/TaskControllerTest.java
│           └── service/TaskServiceTest.java
├── pom.xml
├── Dockerfile
└── k8s/
    ├── deployment.yaml
    └── service.yaml
```

---

## The Code

### Main Application

```java
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

### REST Controller

```java
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
    public ResponseEntity<List<Task>> getAllTasks() {
        return ResponseEntity.ok(taskService.getAllTasks());
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

## Configuration

### application.yml

```yaml
spring:
  application:
    name: demo-app

server:
  port: 8080
  shutdown: graceful

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
```

**Key settings:**
- `shutdown: graceful` — Kubernetes needs this for proper pod termination
- Health probes exposed for Kubernetes readiness/liveness checks
- Prometheus metrics for monitoring

---

## Tests

### Unit Test

```java
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskService taskService;

    @Test
    void createTask_ShouldReturnTaskWithId() {
        Task task = new Task("Test Task");
        when(taskRepository.save(any(Task.class)))
            .thenAnswer(inv -> {
                Task t = inv.getArgument(0);
                t.setId(1L);
                return t;
            });

        Task created = taskService.createTask(task);

        assertNotNull(created.getId());
        assertEquals("Test Task", created.getTitle());
    }
}
```

### Integration Test

```java
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @Test
    void getAllTasks_ShouldReturnTasks() throws Exception {
        when(taskService.getAllTasks())
            .thenReturn(Arrays.asList(new Task("Task 1")));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Task 1")));
    }

    @Test
    void createTask_WithInvalidData_ShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
```

---

## Maven Configuration

### pom.xml (Key Parts)

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.0</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- JaCoCo for code coverage -->
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.11</version>
            <executions>
                <execution>
                    <goals><goal>prepare-agent</goal></goals>
                </execution>
                <execution>
                    <id>report</id>
                    <phase>test</phase>
                    <goals><goal>report</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## The Dockerfile

This is where many projects go wrong. Here's how to do it right:

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Download dependencies first (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

# Security: Non-root user
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# Container-optimized JVM settings
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Why this approach?**

1. **Multi-stage build** — Final image doesn't include Maven, JDK, or source code
2. **Dependency caching** — `pom.xml` copied first, dependencies cached
3. **Non-root user** — Security best practice
4. **Alpine base** — Smaller image (~150MB vs ~400MB)
5. **Container-aware JVM** — Uses `UseContainerSupport` for proper memory detection

---

## Kubernetes Deployment

### deployment.yaml

```yaml
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
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
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
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
```

### service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: demo-app
spec:
  type: ClusterIP
  ports:
    - port: 80
      targetPort: 8080
  selector:
    app: demo-app
```

---

## Building Locally

```bash
cd spring-boot-app

# Run tests
mvn test

# Build JAR
mvn package

# Build Docker image
docker build -t demo-app:latest .

# Run locally
docker run -p 8080:8080 demo-app:latest

# Test endpoints
curl http://localhost:8080/api/tasks
curl http://localhost:8080/actuator/health
```

---

## What We've Built

| Component | Purpose |
|-----------|---------|
| REST API | CRUD operations for tasks |
| Validation | Input validation with error messages |
| Health endpoints | K8s liveness/readiness probes |
| Prometheus metrics | Monitoring integration |
| Unit tests | Service layer testing |
| Integration tests | Controller testing |
| Multi-stage Dockerfile | Optimized container image |
| K8s manifests | Production-ready deployment |

---

## What's Next?

In **Part 6** (the finale!), we'll tie everything together:

- Complete Jenkinsfile with all stages
- Parallel test execution
- Automatic deployments
- Best practices and troubleshooting

The finish line is in sight!

---

**Previous:** [← Part 4: Dynamic Pod Agents](#)

**Next:** [Part 6: Complete Pipeline & Best Practices →](#)

---

*Follow me to get notified when the final part is published!*

---

**Tags:** `Spring Boot` `Docker` `Kubernetes` `Java` `CI/CD`
