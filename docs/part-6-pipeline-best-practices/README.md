# Part 6: Complete Pipeline & Best Practices

In this final part, we'll create a production-ready Jenkins pipeline with security scanning, parallel stages, and deployment strategies.

## Pipeline Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CI/CD Pipeline Stages                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐ │
│   │ Checkout │──►│  Build   │──►│   Test   │──►│ Security │──►│  Deploy  │ │
│   │   Code   │   │ & Cache  │   │  & Scan  │   │  Checks  │   │   App    │ │
│   └──────────┘   └──────────┘   └──────────┘   └──────────┘   └──────────┘ │
│                                                                              │
│                        ▼                ▼                                    │
│                   ┌──────────┐    ┌──────────┐                              │
│                   │  Unit    │    │Security  │                              │
│                   │  Tests   │    │ Scan     │  (Parallel)                  │
│                   └──────────┘    └──────────┘                              │
│                   ┌──────────┐    ┌──────────┐                              │
│                   │Coverage  │    │ SAST     │                              │
│                   │ Report   │    │ Analysis │                              │
│                   └──────────┘    └──────────┘                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Complete Jenkinsfile

```groovy
// Jenkinsfile
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
    - name: kubectl
      image: bitnami/kubectl:latest
      command: ["sleep", "infinity"]
      resources:
        requests:
          cpu: "100m"
          memory: "128Mi"
  volumes:
    - name: maven-cache
      emptyDir: {}
    - name: docker-config
      secret:
        secretName: docker-registry-credentials
        optional: true
'''
        }
    }

    environment {
        APP_NAME = 'demo-app'
        DOCKER_REGISTRY = 'your-registry.com'
        DOCKER_IMAGE = "${DOCKER_REGISTRY}/${APP_NAME}"
        K8S_NAMESPACE = 'production'
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
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT_SHORT}"
                }
            }
        }

        stage('Build') {
            steps {
                container('maven') {
                    dir('spring-boot-app') {
                        sh 'mvn clean compile -B'
                    }
                }
            }
        }

        stage('Test & Quality') {
            parallel {
                stage('Unit Tests') {
                    steps {
                        container('maven') {
                            dir('spring-boot-app') {
                                sh 'mvn test -B'
                            }
                        }
                    }
                    post {
                        always {
                            junit '**/target/surefire-reports/*.xml'
                        }
                    }
                }

                stage('Code Coverage') {
                    steps {
                        container('maven') {
                            dir('spring-boot-app') {
                                sh 'mvn jacoco:report -B'
                            }
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
                container('maven') {
                    dir('spring-boot-app') {
                        sh 'mvn package -DskipTests -B'
                    }
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: '**/target/*.jar'
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                container('kaniko') {
                    dir('spring-boot-app') {
                        sh """
                            /kaniko/executor \
                                --context=. \
                                --dockerfile=Dockerfile \
                                --destination=${DOCKER_IMAGE}:${IMAGE_TAG} \
                                --destination=${DOCKER_IMAGE}:latest \
                                --cache=true \
                                --cache-ttl=24h
                        """
                    }
                }
            }
        }

        stage('Deploy to Staging') {
            when {
                branch 'develop'
            }
            steps {
                container('kubectl') {
                    sh """
                        kubectl set image deployment/${APP_NAME} \
                            ${APP_NAME}=${DOCKER_IMAGE}:${IMAGE_TAG} \
                            -n staging \
                            --record
                    """
                }
            }
        }

        stage('Deploy to Production') {
            when {
                branch 'main'
            }
            steps {
                input message: 'Deploy to Production?', ok: 'Deploy'
                container('kubectl') {
                    sh """
                        kubectl set image deployment/${APP_NAME} \
                            ${APP_NAME}=${DOCKER_IMAGE}:${IMAGE_TAG} \
                            -n ${K8S_NAMESPACE} \
                            --record
                        kubectl rollout status deployment/${APP_NAME} \
                            -n ${K8S_NAMESPACE} \
                            --timeout=300s
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
        }
        failure {
            echo "Pipeline failed!"
        }
    }
}
```

---

## Pipeline Features

### 1. Dynamic Pod Template

The pipeline uses an inline YAML pod template with:
- **JNLP container**: Jenkins agent communication
- **Maven container**: Java builds and tests
- **Kaniko container**: Docker image builds (no Docker daemon)
- **kubectl container**: Kubernetes deployments

### 2. Parallel Stages

Tests and quality checks run in parallel:

```groovy
stage('Test & Quality') {
    parallel {
        stage('Unit Tests') { ... }
        stage('Code Coverage') { ... }
    }
}
```

### 3. Conditional Deployment

Different branches deploy to different environments:

```groovy
when {
    branch 'develop'  // Deploy to staging
}

when {
    branch 'main'     // Deploy to production with approval
}
```

### 4. Manual Approval

Production deployments require manual approval:

```groovy
input message: 'Deploy to Production?', ok: 'Deploy'
```

---

## Best Practices

### 1. Security

```groovy
// Use credentials binding
withCredentials([usernamePassword(
    credentialsId: 'docker-creds',
    usernameVariable: 'USER',
    passwordVariable: 'PASS'
)]) {
    // Use credentials securely
}
```

### 2. Error Handling

```groovy
try {
    sh 'command that might fail'
} catch (Exception e) {
    currentBuild.result = 'UNSTABLE'
    echo "Error: ${e.message}"
}
```

### 3. Notifications

```groovy
post {
    failure {
        slackSend(
            channel: '#builds',
            color: 'danger',
            message: "Build failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
        )
    }
}
```

### 4. Artifact Management

```groovy
// Archive artifacts
archiveArtifacts artifacts: '**/target/*.jar'

// Fingerprint for tracking
fingerprint '**/target/*.jar'
```

---

## Multi-Branch Pipeline

### Jenkinsfile for Different Branches

```groovy
pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                echo "Building ${env.BRANCH_NAME}"
            }
        }

        stage('Deploy') {
            when {
                anyOf {
                    branch 'main'
                    branch 'develop'
                    branch pattern: "release/*", comparator: "GLOB"
                }
            }
            steps {
                script {
                    def environment = env.BRANCH_NAME == 'main' ? 'production' :
                                      env.BRANCH_NAME == 'develop' ? 'staging' : 'test'
                    deploy(environment)
                }
            }
        }
    }
}
```

---

## Shared Libraries

Create reusable pipeline code:

```groovy
// vars/standardPipeline.groovy
def call(Map config = [:]) {
    pipeline {
        agent {
            kubernetes {
                yaml libraryResource('podTemplates/maven.yaml')
            }
        }
        stages {
            stage('Build') {
                steps {
                    container('maven') {
                        sh "mvn clean package -B"
                    }
                }
            }
        }
    }
}

// Jenkinsfile (usage)
@Library('shared-library') _
standardPipeline(app: 'demo-app')
```

---

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| Pod fails to start | Check resource limits and quotas |
| Image pull fails | Verify registry credentials |
| Tests fail | Check test reports in Jenkins |
| Deployment fails | Check kubectl permissions |

### Debug Commands

```bash
# Check agent pods
kubectl get pods -n jenkins -l jenkins/label=agent

# Get pod logs
kubectl logs -n jenkins <pod-name> -c maven

# Check pipeline console output
# Jenkins UI > Build > Console Output
```

---

## Summary

Congratulations! You've completed the Jenkins on Kubernetes series. You now have:

1. **Secure Kubernetes Foundation** - Namespace, RBAC, Network Policies
2. **Production-Ready Jenkins** - High availability, security hardening
3. **Dynamic Pod Agents** - Auto-scaling, resource-efficient builds
4. **Sample Application** - Spring Boot with tests and Docker
5. **Complete Pipeline** - CI/CD with best practices

---

## What's Next?

- Add SonarQube for code quality analysis
- Implement GitOps with ArgoCD
- Add vulnerability scanning with Trivy
- Set up monitoring with Prometheus/Grafana
- Implement secrets management with Vault

---

## Resources

- [Jenkins Pipeline Documentation](https://www.jenkins.io/doc/book/pipeline/)
- [Kubernetes Plugin](https://plugins.jenkins.io/kubernetes/)
- [Jenkins Configuration as Code](https://www.jenkins.io/projects/jcasc/)
- [Kaniko Documentation](https://github.com/GoogleContainerTools/kaniko)

---

[← Back to Part 5](../part-5-spring-boot/README.md) | [Back to Introduction →](../part-1-introduction/README.md)

---

*This concludes the 6-part series on deploying Jenkins on Kubernetes with security best practices. Happy building!*
