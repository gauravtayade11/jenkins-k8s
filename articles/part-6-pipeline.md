# Jenkins on Kubernetes: The Complete Pipeline (Part 6 — Finale)

*Putting it all together with a production-ready Jenkinsfile*

---

We've come a long way.

- Part 1: We understood the architecture
- Part 2: We built a secure Kubernetes foundation
- Part 3: We deployed Jenkins with security hardening
- Part 4: We configured dynamic pod agents
- Part 5: We created a real Spring Boot application

Now it's time for the grand finale: **a complete CI/CD pipeline that ties everything together**.

By the end of this article, you'll have a production-ready Jenkinsfile that:
- Builds, tests, and packages your application
- Runs parallel test stages for speed
- Builds Docker images without Docker (Kaniko)
- Deploys to Kubernetes
- Includes proper error handling and notifications

Let's finish this.

---

## The Complete Pipeline

Here's our battle-tested Jenkinsfile:

```groovy
pipeline {
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: agent
spec:
  serviceAccountName: jenkins
  securityContext:
    runAsUser: 1000
    fsGroup: 1000
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest-jdk17
      resources:
        requests:
          cpu: "100m"
          memory: "256Mi"

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
      volumeMounts:
        - name: maven-cache
          mountPath: /root/.m2/repository

    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/sleep", "infinity"]
      volumeMounts:
        - name: docker-config
          mountPath: /kaniko/.docker

    - name: kubectl
      image: bitnami/kubectl:latest
      command: ["sleep", "infinity"]

  volumes:
    - name: maven-cache
      emptyDir: {}
    - name: docker-config
      secret:
        secretName: docker-registry-credentials
        optional: true
'''
            defaultContainer: 'maven'
        }
    }

    environment {
        APP_NAME = 'demo-app'
        DOCKER_REGISTRY = 'your-registry.com'
        DOCKER_IMAGE = "${DOCKER_REGISTRY}/${APP_NAME}"
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()
                    env.IMAGE_TAG = "${BUILD_NUMBER}-${GIT_COMMIT_SHORT}"
                }
            }
        }

        stage('Build') {
            steps {
                dir('spring-boot-app') {
                    sh 'mvn clean compile -B'
                }
            }
        }

        stage('Test') {
            parallel {
                stage('Unit Tests') {
                    steps {
                        dir('spring-boot-app') {
                            sh 'mvn test -B'
                        }
                    }
                    post {
                        always {
                            junit '**/target/surefire-reports/*.xml'
                        }
                    }
                }
                stage('Coverage') {
                    steps {
                        dir('spring-boot-app') {
                            sh 'mvn jacoco:report -B'
                        }
                    }
                    post {
                        always {
                            publishHTML([
                                reportDir: 'spring-boot-app/target/site/jacoco',
                                reportFiles: 'index.html',
                                reportName: 'Coverage Report'
                            ])
                        }
                    }
                }
            }
        }

        stage('Package') {
            steps {
                dir('spring-boot-app') {
                    sh 'mvn package -DskipTests -B'
                }
            }
            post {
                success {
                    archiveArtifacts 'spring-boot-app/target/*.jar'
                }
            }
        }

        stage('Docker Build') {
            steps {
                container('kaniko') {
                    dir('spring-boot-app') {
                        sh """
                            /kaniko/executor \
                                --context=. \
                                --dockerfile=Dockerfile \
                                --destination=${DOCKER_IMAGE}:${IMAGE_TAG} \
                                --destination=${DOCKER_IMAGE}:latest \
                                --cache=true
                        """
                    }
                }
            }
        }

        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                input message: 'Deploy to production?', ok: 'Deploy'
                container('kubectl') {
                    sh """
                        kubectl set image deployment/${APP_NAME} \
                            ${APP_NAME}=${DOCKER_IMAGE}:${IMAGE_TAG} \
                            -n production --record
                        kubectl rollout status deployment/${APP_NAME} \
                            -n production --timeout=300s
                    """
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
        success {
            echo "Pipeline completed successfully!"
            echo "Image: ${DOCKER_IMAGE}:${IMAGE_TAG}"
        }
        failure {
            echo "Pipeline failed!"
        }
    }
}
```

---

## Pipeline Breakdown

### Stage 1: Checkout

```groovy
stage('Checkout') {
    steps {
        checkout scm
        script {
            env.GIT_COMMIT_SHORT = sh(
                script: 'git rev-parse --short HEAD',
                returnStdout: true
            ).trim()
            env.IMAGE_TAG = "${BUILD_NUMBER}-${GIT_COMMIT_SHORT}"
        }
    }
}
```

**What it does:**
- Checks out the source code
- Creates a unique image tag: `42-a1b2c3d` (build number + commit hash)

**Why:** Unique tags enable rollbacks and traceability.

---

### Stage 2: Build

```groovy
stage('Build') {
    steps {
        dir('spring-boot-app') {
            sh 'mvn clean compile -B'
        }
    }
}
```

**The `-B` flag:** Batch mode — no interactive prompts, cleaner logs.

---

### Stage 3: Parallel Tests

```groovy
stage('Test') {
    parallel {
        stage('Unit Tests') { ... }
        stage('Coverage') { ... }
    }
}
```

**Why parallel?** Unit tests and coverage reports are independent. Running them in parallel saves time.

On a typical project, this can cut test time by 30-50%.

---

### Stage 4: Docker Build with Kaniko

```groovy
container('kaniko') {
    sh """
        /kaniko/executor \
            --context=. \
            --dockerfile=Dockerfile \
            --destination=${DOCKER_IMAGE}:${IMAGE_TAG} \
            --destination=${DOCKER_IMAGE}:latest \
            --cache=true
    """
}
```

**Key flags:**
- `--cache=true` — Caches layers for faster subsequent builds
- Two `--destination` flags — Tags with both specific version and `latest`

---

### Stage 5: Deployment with Approval

```groovy
stage('Deploy') {
    when {
        branch 'main'
    }
    steps {
        input message: 'Deploy to production?', ok: 'Deploy'
        container('kubectl') {
            sh "kubectl set image deployment/..."
        }
    }
}
```

**Safety features:**
- Only runs on `main` branch
- Requires manual approval
- Uses rolling update (`set image`)
- Waits for rollout to complete

---

## Multi-Branch Strategy

Different branches, different behaviors:

```groovy
stage('Deploy') {
    when {
        anyOf {
            branch 'main'
            branch 'develop'
            branch pattern: 'release/*', comparator: 'GLOB'
        }
    }
    steps {
        script {
            def env = branch == 'main' ? 'production' :
                      branch == 'develop' ? 'staging' : 'test'
            deploy(env)
        }
    }
}
```

| Branch | Environment |
|--------|-------------|
| `main` | Production |
| `develop` | Staging |
| `release/*` | Test |
| Feature branches | Build & test only |

---

## Best Practices

### 1. Fail Fast

Put quick checks early:

```groovy
stages {
    stage('Lint') { ... }      // Seconds
    stage('Unit Test') { ... } // Minutes
    stage('Build') { ... }     // Minutes
    stage('Integration') { ... } // Longer
}
```

### 2. Don't Repeat Yourself

Use shared libraries for common patterns:

```groovy
// vars/standardPipeline.groovy
def call(Map config) {
    pipeline {
        agent { kubernetes { yaml libraryResource('maven-pod.yaml') } }
        stages {
            stage('Build') { ... }
            stage('Test') { ... }
        }
    }
}

// Jenkinsfile
@Library('shared-lib') _
standardPipeline(app: 'demo-app')
```

### 3. Secure Credentials

Never hardcode secrets:

```groovy
withCredentials([
    usernamePassword(
        credentialsId: 'docker-creds',
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS'
    )
]) {
    sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
}
```

### 4. Meaningful Notifications

```groovy
post {
    failure {
        slackSend(
            channel: '#builds',
            color: 'danger',
            message: """
                *Build Failed*
                Job: ${JOB_NAME}
                Build: #${BUILD_NUMBER}
                Commit: ${GIT_COMMIT_SHORT}
                Author: ${GIT_AUTHOR_NAME}
            """
        )
    }
}
```

---

## Troubleshooting Guide

### Build Fails: "Cannot pull image"

```bash
kubectl describe pod -n jenkins <pod-name>
```

Check:
- Image name is correct
- Registry credentials exist
- Network policy allows egress

### Build Fails: "Permission denied"

The container is running as non-root but trying to write to root-owned directory.

Solution:
```yaml
securityContext:
  fsGroup: 1000  # Ensures volume is writable
```

### Deploy Fails: "Unauthorized"

RBAC issue. Check service account permissions:

```bash
kubectl auth can-i update deployments \
    --as=system:serviceaccount:jenkins:jenkins \
    -n production
```

### Pipeline Timeout

Increase the timeout or optimize the slow stage:

```groovy
options {
    timeout(time: 60, unit: 'MINUTES')
}

// Or per-stage
stage('Slow Stage') {
    options {
        timeout(time: 30, unit: 'MINUTES')
    }
}
```

---

## The Complete Picture

Let's step back and appreciate what we've built:

```
┌──────────────────────────────────────────────────────────────────┐
│                    Complete CI/CD Platform                        │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Code Push ──► Jenkins ──► Dynamic Agent ──► Build & Test        │
│                   │              │                 │              │
│                   │              │                 ▼              │
│                   │              │          Kaniko Build          │
│                   │              │                 │              │
│                   │              │                 ▼              │
│                   │              │         Push to Registry       │
│                   │              │                 │              │
│                   │              │                 ▼              │
│                   │              └────────► K8s Deployment        │
│                   │                              │                │
│                   └────────────────────────────► Notifications    │
│                                                                   │
│  🔒 Security: RBAC, Network Policies, Non-root, Secrets          │
│  📈 Scaling: Dynamic agents, zero idle resources                 │
│  🚀 Speed: Parallel tests, cached dependencies                   │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## What We Achieved

| Goal | Status |
|------|--------|
| Secure namespace with RBAC | ✅ |
| Network isolation | ✅ |
| Dynamic pod agents | ✅ |
| Zero idle resources | ✅ |
| Docker builds without Docker | ✅ |
| Complete CI/CD pipeline | ✅ |
| Production-ready Spring Boot app | ✅ |

---

## What's Next for You?

This is a solid foundation. Here's how to take it further:

1. **Add SonarQube** — Code quality gates
2. **Add Trivy** — Container vulnerability scanning
3. **Implement GitOps** — ArgoCD for declarative deployments
4. **Add Vault** — External secrets management
5. **Set up monitoring** — Prometheus + Grafana dashboards

---

## Final Thoughts

Building Jenkins on Kubernetes the right way takes effort. But the payoff is enormous:

- **Cost savings** from dynamic scaling
- **Security** that lets you sleep at night
- **Reliability** that your team can depend on
- **Speed** that keeps developers happy

I hope this series has given you everything you need to build your own production-ready CI/CD platform.

**Now go build something amazing.**

---

## Resources

- [GitHub Repository](https://github.com/yourusername/jenkins-k8s) — All code from this series
- [Jenkins Kubernetes Plugin](https://plugins.jenkins.io/kubernetes/)
- [Kaniko Documentation](https://github.com/GoogleContainerTools/kaniko)
- [Jenkins Configuration as Code](https://www.jenkins.io/projects/jcasc/)

---

**Previous:** [← Part 5: Spring Boot Sample Project](#)

**Back to Start:** [Part 1: Introduction →](#)

---

*Thank you for following this series! If you found it helpful, please share it with your team. And don't forget to star the repo!*

---

**Tags:** `Jenkins` `Kubernetes` `CI/CD` `DevOps` `Pipeline`
