# Jenkins on Kubernetes: Dynamic Pod Agents That Scale to Zero (Part 4)

*The secret sauce that makes Jenkins on Kubernetes actually worth it*

---

Here's a question: **How much do you pay for idle build agents?**

If you're running traditional Jenkins with static agents, the answer is probably "more than you think." Those VMs or containers sitting there, waiting for builds, consuming resources 24/7.

Now imagine a world where:
- Agents appear instantly when a build starts
- Each build gets a fresh, isolated environment
- Agents disappear the moment the build finishes
- You pay only for actual build time

That's exactly what we're building today.

In this article, you'll learn:
- How dynamic pod agents work
- Creating pod templates for different build types
- Multi-container pods for complex pipelines
- Optimization tricks for faster builds

Let's make Jenkins scale like it was born in the cloud.

---

## How Dynamic Agents Work

Here's the lifecycle:

```
1. Build Triggered     2. Pod Created      3. Build Runs       4. Pod Deleted
       │                     │                   │                   │
       ▼                     ▼                   ▼                   ▼
  ┌──────────┐         ┌──────────┐        ┌──────────┐       ┌──────────┐
  │ Jenkins  │────────►│   K8s    │───────►│  Agent   │──────►│  Clean   │
  │ Queues   │ Request │ Creates  │  Run   │ Executes │ Done  │    Up    │
  │  Build   │   Pod   │   Pod    │ Build  │  Build   │       │   Pod    │
  └──────────┘         └──────────┘        └──────────┘       └──────────┘
```

**The magic:**
1. Jenkins receives a build trigger
2. Kubernetes plugin requests a new pod
3. Pod starts with JNLP agent + build tools
4. Agent connects to Jenkins, runs the build
5. Build completes, pod is terminated
6. Resources returned to the cluster

**Result:** You only pay for what you use.

---

## Understanding Pod Templates

A pod template defines what containers and resources an agent needs.

### The Anatomy of an Agent Pod

```
┌─────────────────────────────────────────────────────┐
│                    Agent Pod                         │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │
│  │    JNLP     │ │   Maven     │ │   Kaniko    │   │
│  │  Container  │ │  Container  │ │  Container  │   │
│  │  (required) │ │  (builds)   │ │  (docker)   │   │
│  └─────────────┘ └─────────────┘ └─────────────┘   │
│                        │                            │
│                   Shared Volumes                    │
│            (workspace, maven-cache, etc.)           │
└─────────────────────────────────────────────────────┘
```

**Key insight:** Multiple containers share the same pod, same network, same volumes. This enables powerful multi-tool pipelines.

---

## Pod Template 1: Maven Agent

For Java builds, we need Maven with JDK:

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: agent
    agent-type: maven
spec:
  serviceAccountName: jenkins
  securityContext:
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
  containers:
    # JNLP - Required for Jenkins communication
    - name: jnlp
      image: jenkins/inbound-agent:latest-jdk17
      resources:
        requests:
          cpu: "100m"
          memory: "256Mi"
        limits:
          cpu: "500m"
          memory: "512Mi"

    # Maven - For building Java apps
    - name: maven
      image: maven:3.9-eclipse-temurin-17
      command: ["sleep", "infinity"]
      resources:
        requests:
          cpu: "500m"
          memory: "1Gi"
        limits:
          cpu: "2000m"
          memory: "4Gi"
      env:
        - name: MAVEN_OPTS
          value: "-Xmx2g -XX:+UseG1GC"
      volumeMounts:
        - name: maven-cache
          mountPath: /root/.m2/repository

  volumes:
    - name: maven-cache
      emptyDir: {}
```

**Pro tip:** The `sleep infinity` command keeps the container alive while Jenkins runs commands in it.

---

## Pod Template 2: Node.js Agent

For JavaScript/TypeScript projects:

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: agent
    agent-type: nodejs
spec:
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest-jdk17
      resources:
        requests:
          cpu: "100m"
          memory: "256Mi"

    - name: nodejs
      image: node:20-alpine
      command: ["sleep", "infinity"]
      resources:
        requests:
          cpu: "500m"
          memory: "512Mi"
        limits:
          cpu: "2000m"
          memory: "2Gi"
      volumeMounts:
        - name: npm-cache
          mountPath: /home/node/.npm

  volumes:
    - name: npm-cache
      emptyDir: {}
```

---

## Pod Template 3: Kaniko (Docker Without Docker)

Here's a common challenge: **How do you build Docker images inside Kubernetes without running Docker-in-Docker?**

Answer: **Kaniko**.

Kaniko builds Docker images without needing a Docker daemon. No privileged containers, no security risks.

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: agent
    agent-type: kaniko
spec:
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest-jdk17

    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/sleep", "infinity"]
      resources:
        requests:
          cpu: "500m"
          memory: "1Gi"
        limits:
          cpu: "2000m"
          memory: "4Gi"
      volumeMounts:
        - name: docker-config
          mountPath: /kaniko/.docker
          readOnly: true

  volumes:
    - name: docker-config
      secret:
        secretName: docker-registry-credentials
        items:
          - key: .dockerconfigjson
            path: config.json
```

Create the registry secret:
```bash
kubectl create secret docker-registry docker-registry-credentials \
  --docker-server=your-registry.com \
  --docker-username=your-user \
  --docker-password=your-password \
  -n jenkins
```

---

## Pod Template 4: Multi-Tool Agent

For complex pipelines that need everything:

```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest-jdk17

    - name: maven
      image: maven:3.9-eclipse-temurin-17
      command: ["sleep", "infinity"]

    - name: nodejs
      image: node:20-alpine
      command: ["sleep", "infinity"]

    - name: kubectl
      image: bitnami/kubectl:latest
      command: ["sleep", "infinity"]

    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/sleep", "infinity"]
```

One pod, all the tools you need.

---

## Using Pod Templates in Pipelines

### Declarative Pipeline

```groovy
pipeline {
    agent {
        kubernetes {
            label 'maven'
            defaultContainer 'maven'
        }
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package'
            }
        }
    }
}
```

### Inline Pod Definition

For maximum control, define the pod inline:

```groovy
pipeline {
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: maven
      image: maven:3.9-eclipse-temurin-17
      command: ["sleep", "infinity"]
    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/sleep", "infinity"]
'''
            defaultContainer: 'maven'
        }
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package'
            }
        }
        stage('Docker') {
            steps {
                container('kaniko') {
                    sh '/kaniko/executor --destination=myapp:latest'
                }
            }
        }
    }
}
```

**Notice the `container()` step** — it switches to a different container within the same pod.

---

## Optimization: Faster Builds

### 1. Dependency Caching

Without caching, Maven downloads dependencies every build. Painful.

**Solution:** Persistent Volume for cache

```yaml
volumes:
  - name: maven-cache
    persistentVolumeClaim:
      claimName: maven-cache-pvc  # ReadWriteMany PVC

# In container:
volumeMounts:
  - name: maven-cache
    mountPath: /root/.m2/repository
```

### 2. Right-Size Resources

| Build Type | CPU Request | Memory Request |
|------------|-------------|----------------|
| Simple builds | 200m | 512Mi |
| Maven/Gradle | 500m | 1Gi |
| Large compilations | 1000m | 2Gi |

**Don't over-provision** — Kubernetes can scale horizontally instead.

### 3. Image Pull Optimization

Pull images from a local registry mirror to avoid rate limits and speed up starts.

```yaml
spec:
  imagePullSecrets:
    - name: registry-credentials
  containers:
    - name: maven
      image: my-registry.local/maven:3.9-eclipse-temurin-17
```

---

## Monitoring Your Agents

### Watch Agent Pods

```bash
kubectl get pods -n jenkins -l jenkins/label=agent -w
```

### Check Resource Usage

```bash
kubectl top pods -n jenkins -l jenkins/label=agent
```

### Prometheus Metrics

The Kubernetes plugin exposes metrics:
- `jenkins_kubernetes_running_pods`
- `jenkins_kubernetes_pending_pods`
- `jenkins_kubernetes_terminated_pods`

---

## Troubleshooting

### Agent Won't Start

```bash
kubectl describe pod -n jenkins <pod-name>
```

Check for:
- Image pull errors
- Resource quota exceeded
- Network policy blocking

### Agent Disconnects Mid-Build

Check JNLP connectivity:
```bash
kubectl exec -n jenkins <controller-pod> -- nc -vz jenkins-agent 50000
```

### Builds Are Slow

1. Check if dependencies are being cached
2. Verify resource limits aren't too low
3. Check network policies allow external access

---

## What We've Built

| Feature | Benefit |
|---------|---------|
| Dynamic scaling | Zero idle resources |
| Multi-container pods | All tools in one place |
| Kaniko | Secure Docker builds |
| Caching | Faster subsequent builds |
| Resource limits | Fair cluster sharing |

---

## What's Next?

In **Part 5**, we'll create a real application to test our pipeline:

- Spring Boot REST API
- Unit and integration tests
- Multi-stage Dockerfile
- Kubernetes deployment manifests

It's time to see everything work together.

---

**Previous:** [← Part 3: Jenkins Deployment](#)

**Next:** [Part 5: Spring Boot Sample Project →](#)

---

*Follow me to get notified when new parts are published!*

---

**Tags:** `Jenkins` `Kubernetes` `CI/CD` `DevOps` `Kaniko`
