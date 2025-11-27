# Part 4: Dynamic Pod Agents Configuration

In this part, we'll configure dynamic pod agents that scale automatically based on demand. This is where Kubernetes really shines for CI/CD workloads.

## How Dynamic Pod Agents Work

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Dynamic Agent Lifecycle                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   1. Build Triggered    2. Pod Created     3. Build Runs      4. Pod Deleted │
│         │                     │                  │                  │        │
│         ▼                     ▼                  ▼                  ▼        │
│   ┌──────────┐          ┌──────────┐        ┌──────────┐      ┌──────────┐  │
│   │ Jenkins  │──────►   │   K8s    │──────► │  Agent   │──────►│  Clean   │  │
│   │ Queue    │ Request  │ Creates  │ Start  │ Executes │ Done  │    Up    │  │
│   │ Build    │  Pod     │   Pod    │ Build  │  Build   │       │   Pod    │  │
│   └──────────┘          └──────────┘        └──────────┘      └──────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Pod Template Concepts

### Multi-Container Pods

Each agent pod can have multiple containers:

```
┌─────────────────────────────────────────────────────┐
│                    Agent Pod                         │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │
│  │    JNLP     │ │   Maven     │ │   Docker    │   │
│  │  Container  │ │  Container  │ │  Container  │   │
│  │  (required) │ │  (builds)   │ │  (optional) │   │
│  └─────────────┘ └─────────────┘ └─────────────┘   │
│                        │                            │
│                   Shared Volumes                    │
│            (workspace, maven-cache, etc.)           │
└─────────────────────────────────────────────────────┘
```

---

## Pod Templates Configuration

### 1. Maven Build Agent

```yaml
# k8s/pod-templates/maven-agent.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: maven-agent-template
  namespace: jenkins
  labels:
    app: jenkins
    template: maven
data:
  pod-template.yaml: |
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
        runAsNonRoot: true
      containers:
        # JNLP container - required for Jenkins communication
        - name: jnlp
          image: jenkins/inbound-agent:latest-jdk17
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

        # Maven container - for builds
        - name: maven
          image: maven:3.9-eclipse-temurin-17
          command:
            - sleep
          args:
            - "infinity"
          resources:
            requests:
              cpu: "500m"
              memory: "1Gi"
            limits:
              cpu: "2000m"
              memory: "4Gi"
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
          volumeMounts:
            - name: maven-cache
              mountPath: /root/.m2/repository
            - name: maven-settings
              mountPath: /root/.m2/settings.xml
              subPath: settings.xml

      volumes:
        - name: maven-cache
          persistentVolumeClaim:
            claimName: maven-cache-pvc
        - name: maven-settings
          configMap:
            name: maven-settings
```

### 2. Node.js Build Agent

```yaml
# k8s/pod-templates/nodejs-agent.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: nodejs-agent-template
  namespace: jenkins
  labels:
    app: jenkins
    template: nodejs
data:
  pod-template.yaml: |
    apiVersion: v1
    kind: Pod
    metadata:
      labels:
        jenkins/label: agent
        agent-type: nodejs
    spec:
      serviceAccountName: jenkins
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true
      containers:
        - name: jnlp
          image: jenkins/inbound-agent:latest-jdk17
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

        - name: nodejs
          image: node:20-alpine
          command:
            - sleep
          args:
            - "infinity"
          resources:
            requests:
              cpu: "500m"
              memory: "512Mi"
            limits:
              cpu: "2000m"
              memory: "2Gi"
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
          volumeMounts:
            - name: npm-cache
              mountPath: /home/node/.npm

      volumes:
        - name: npm-cache
          emptyDir: {}
```

### 3. Docker Build Agent (with Kaniko)

```yaml
# k8s/pod-templates/kaniko-agent.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: kaniko-agent-template
  namespace: jenkins
  labels:
    app: jenkins
    template: kaniko
data:
  pod-template.yaml: |
    apiVersion: v1
    kind: Pod
    metadata:
      labels:
        jenkins/label: agent
        agent-type: kaniko
    spec:
      serviceAccountName: jenkins
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
      containers:
        - name: jnlp
          image: jenkins/inbound-agent:latest-jdk17
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

        # Kaniko for building Docker images without Docker daemon
        - name: kaniko
          image: gcr.io/kaniko-project/executor:debug
          command:
            - sleep
          args:
            - "infinity"
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

      volumes:
        - name: docker-config
          secret:
            secretName: docker-registry-credentials
            items:
              - key: .dockerconfigjson
                path: config.json
```

### 4. Multi-Tool Agent (All-in-One)

```yaml
# k8s/pod-templates/multi-tool-agent.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: multi-tool-agent-template
  namespace: jenkins
  labels:
    app: jenkins
    template: multi-tool
data:
  pod-template.yaml: |
    apiVersion: v1
    kind: Pod
    metadata:
      labels:
        jenkins/label: agent
        agent-type: multi-tool
    spec:
      serviceAccountName: jenkins
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true
      containers:
        - name: jnlp
          image: jenkins/inbound-agent:latest-jdk17
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

        - name: maven
          image: maven:3.9-eclipse-temurin-17
          command: ["sleep", "infinity"]
          resources:
            requests:
              cpu: "200m"
              memory: "512Mi"
            limits:
              cpu: "1000m"
              memory: "2Gi"
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
          volumeMounts:
            - name: maven-cache
              mountPath: /root/.m2/repository

        - name: kubectl
          image: bitnami/kubectl:latest
          command: ["sleep", "infinity"]
          resources:
            requests:
              cpu: "100m"
              memory: "128Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL

        - name: kaniko
          image: gcr.io/kaniko-project/executor:debug
          command: ["sleep", "infinity"]
          resources:
            requests:
              cpu: "200m"
              memory: "512Mi"
            limits:
              cpu: "1000m"
              memory: "2Gi"
          volumeMounts:
            - name: docker-config
              mountPath: /kaniko/.docker

      volumes:
        - name: maven-cache
          emptyDir: {}
        - name: docker-config
          secret:
            secretName: docker-registry-credentials
            optional: true
            items:
              - key: .dockerconfigjson
                path: config.json
```

---

## JCasC Pod Template Configuration

Add these templates to your Jenkins Configuration as Code:

```yaml
# Add to k8s/jenkins/configmap.yaml in JCasC section
jenkins:
  clouds:
    - kubernetes:
        name: "kubernetes"
        serverUrl: "https://kubernetes.default.svc"
        namespace: "jenkins"
        jenkinsUrl: "http://jenkins.jenkins.svc.cluster.local:8080"
        jenkinsTunnel: "jenkins-agent.jenkins.svc.cluster.local:50000"
        containerCapStr: "50"
        podRetention: onFailure
        podLabels:
          - key: "jenkins/label"
            value: "agent"

        templates:
          # Default agent
          - name: "default"
            label: "jenkins-agent default"
            nodeUsageMode: NORMAL
            containers:
              - name: "jnlp"
                image: "jenkins/inbound-agent:latest-jdk17"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "100m"
                resourceRequestMemory: "256Mi"
                resourceLimitCpu: "500m"
                resourceLimitMemory: "512Mi"

          # Maven agent
          - name: "maven"
            label: "maven"
            nodeUsageMode: EXCLUSIVE
            containers:
              - name: "jnlp"
                image: "jenkins/inbound-agent:latest-jdk17"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "100m"
                resourceRequestMemory: "256Mi"
                resourceLimitCpu: "500m"
                resourceLimitMemory: "512Mi"
              - name: "maven"
                image: "maven:3.9-eclipse-temurin-17"
                command: "sleep"
                args: "infinity"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "500m"
                resourceRequestMemory: "1Gi"
                resourceLimitCpu: "2000m"
                resourceLimitMemory: "4Gi"
            volumes:
              - emptyDirVolume:
                  mountPath: "/root/.m2/repository"
                  memory: false

          # Node.js agent
          - name: "nodejs"
            label: "nodejs"
            nodeUsageMode: EXCLUSIVE
            containers:
              - name: "jnlp"
                image: "jenkins/inbound-agent:latest-jdk17"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "100m"
                resourceRequestMemory: "256Mi"
              - name: "nodejs"
                image: "node:20-alpine"
                command: "sleep"
                args: "infinity"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "500m"
                resourceRequestMemory: "512Mi"
                resourceLimitCpu: "2000m"
                resourceLimitMemory: "2Gi"

          # Kaniko agent (for Docker builds)
          - name: "kaniko"
            label: "kaniko docker"
            nodeUsageMode: EXCLUSIVE
            containers:
              - name: "jnlp"
                image: "jenkins/inbound-agent:latest-jdk17"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "100m"
                resourceRequestMemory: "256Mi"
              - name: "kaniko"
                image: "gcr.io/kaniko-project/executor:debug"
                command: "sleep"
                args: "infinity"
                workingDir: "/home/jenkins/agent"
                resourceRequestCpu: "500m"
                resourceRequestMemory: "1Gi"
                resourceLimitCpu: "2000m"
                resourceLimitMemory: "4Gi"
```

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

### Scripted Pipeline with Custom Pod

```groovy
podTemplate(
    label: 'custom-pod',
    containers: [
        containerTemplate(
            name: 'maven',
            image: 'maven:3.9-eclipse-temurin-17',
            command: 'sleep',
            args: 'infinity',
            resourceRequestCpu: '500m',
            resourceRequestMemory: '1Gi'
        ),
        containerTemplate(
            name: 'kubectl',
            image: 'bitnami/kubectl:latest',
            command: 'sleep',
            args: 'infinity'
        )
    ],
    volumes: [
        emptyDirVolume(mountPath: '/root/.m2/repository', memory: false)
    ]
) {
    node('custom-pod') {
        stage('Build') {
            container('maven') {
                sh 'mvn clean package'
            }
        }
        stage('Deploy') {
            container('kubectl') {
                sh 'kubectl apply -f k8s/'
            }
        }
    }
}
```

---

## Optimization Strategies

### 1. Caching Dependencies

Use persistent volumes for caching:

```yaml
# k8s/pod-templates/maven-cache-pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: maven-cache-pvc
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteMany  # RWX for shared cache
  resources:
    requests:
      storage: 10Gi
  storageClassName: nfs  # Or any RWX storage class
```

### 2. Using Init Containers for Pre-warming

```yaml
initContainers:
  - name: download-dependencies
    image: maven:3.9-eclipse-temurin-17
    command: ['mvn', 'dependency:go-offline']
    volumeMounts:
      - name: maven-cache
        mountPath: /root/.m2/repository
```

### 3. Resource Right-sizing

| Build Type | CPU Request | CPU Limit | Memory Request | Memory Limit |
|------------|-------------|-----------|----------------|--------------|
| Simple builds | 200m | 1000m | 512Mi | 2Gi |
| Maven builds | 500m | 2000m | 1Gi | 4Gi |
| Large compilations | 1000m | 4000m | 2Gi | 8Gi |

### 4. Pod Affinity for Cache Locality

```yaml
affinity:
  podAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              app: maven-cache
```

---

## Monitoring Agents

### Prometheus Metrics

The Kubernetes plugin exposes metrics:

- `jenkins_kubernetes_running_pods` - Current running agent pods
- `jenkins_kubernetes_pending_pods` - Pods waiting to start
- `jenkins_kubernetes_terminated_pods` - Pods that have terminated

### Useful Commands

```bash
# Watch agent pods
kubectl get pods -n jenkins -l jenkins/label=agent -w

# Get agent pod logs
kubectl logs -n jenkins -l jenkins/label=agent --all-containers

# Check resource usage
kubectl top pods -n jenkins -l jenkins/label=agent
```

---

## Troubleshooting

### Agent Won't Start

```bash
# Check pod events
kubectl describe pod -n jenkins <agent-pod-name>

# Common issues:
# - Image pull errors
# - Insufficient resources
# - PVC not bound
# - Network policy blocking
```

### Agent Disconnects

Check JNLP port connectivity:

```bash
# Test from a pod in the same namespace
kubectl run test --rm -it --image=busybox -n jenkins -- nc -vz jenkins 50000
```

### Build Runs Slow

- Check resource limits (are they too low?)
- Check cache volumes (are dependencies downloading every time?)
- Check network policies (are external repos accessible?)

---

## Next Part

In **Part 5**, we'll create a Spring Boot sample project:
- Application code
- Unit and integration tests
- Dockerfile with multi-stage build
- Kubernetes deployment manifests

[← Back to Part 3](../part-3-jenkins-deployment/README.md) | [Continue to Part 5: Spring Boot Sample Project →](../part-5-spring-boot/README.md)

---

*This is Part 4 of a 6-part series on deploying Jenkins on Kubernetes with security best practices.*
