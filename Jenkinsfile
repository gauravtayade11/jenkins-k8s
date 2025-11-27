// ============================================
// Jenkins Pipeline for Spring Boot on Kubernetes
// ============================================
// This pipeline demonstrates:
// - Dynamic Kubernetes pod agents
// - Multi-container builds
// - Parallel testing
// - Docker image building with Kaniko
// - Kubernetes deployment
// ============================================

pipeline {
    agent {
        kubernetes {
            // Inline pod template for dynamic agent
            yaml '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: agent
    build-type: spring-boot
spec:
  serviceAccountName: jenkins
  securityContext:
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
  containers:
    # JNLP container for Jenkins agent communication
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

    # Maven container for building Java applications
    - name: maven
      image: maven:3.9-eclipse-temurin-17
      command: ["sleep", "infinity"]
      workingDir: /home/jenkins/agent
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
      env:
        - name: MAVEN_OPTS
          value: "-Xmx2g -Xms512m -XX:+UseG1GC"
        - name: MAVEN_CONFIG
          value: "/home/jenkins/.m2"
      volumeMounts:
        - name: maven-cache
          mountPath: /home/jenkins/.m2/repository
        - name: workspace
          mountPath: /home/jenkins/agent

    # Kaniko container for building Docker images without Docker daemon
    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/sleep", "infinity"]
      workingDir: /workspace
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
        - name: workspace
          mountPath: /workspace

    # kubectl container for Kubernetes deployments
    - name: kubectl
      image: bitnami/kubectl:latest
      command: ["sleep", "infinity"]
      workingDir: /home/jenkins/agent
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
      volumeMounts:
        - name: workspace
          mountPath: /home/jenkins/agent

  volumes:
    # Maven dependency cache (speeds up builds)
    - name: maven-cache
      emptyDir:
        sizeLimit: 5Gi
    # Docker registry credentials for Kaniko
    - name: docker-config
      secret:
        secretName: docker-registry-credentials
        optional: true
        items:
          - key: .dockerconfigjson
            path: config.json
    # Shared workspace
    - name: workspace
      emptyDir: {}

  # Node selection (optional)
  # nodeSelector:
  #   node-role.kubernetes.io/build: "true"
'''
            // Use default container for steps
            defaultContainer: 'maven'
        }
    }

    // Environment variables
    environment {
        APP_NAME = 'demo-app'
        APP_VERSION = '1.0.0'
        // Update these for your environment
        DOCKER_REGISTRY = 'your-registry.azurecr.io'  // or gcr.io, docker.io
        DOCKER_IMAGE = "${DOCKER_REGISTRY}/${APP_NAME}"
        K8S_NAMESPACE_STAGING = 'staging'
        K8S_NAMESPACE_PROD = 'production'
    }

    // Pipeline options
    options {
        // Timeout after 30 minutes
        timeout(time: 30, unit: 'MINUTES')
        // Don't run concurrent builds for same branch
        disableConcurrentBuilds()
        // Keep last 10 builds
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // Add timestamps to console output
        timestamps()
        // Skip default checkout (we do it manually)
        skipDefaultCheckout(true)
    }

    // Build parameters (optional)
    parameters {
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip running tests'
        )
        booleanParam(
            name: 'DEPLOY_TO_PROD',
            defaultValue: false,
            description: 'Deploy directly to production (requires main branch)'
        )
    }

    stages {
        // ============================================
        // Stage 1: Checkout Source Code
        // ============================================
        stage('Checkout') {
            steps {
                checkout scm

                script {
                    // Get short commit hash for tagging
                    env.GIT_COMMIT_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()

                    // Create unique image tag
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_COMMIT_SHORT}"

                    // Get branch name
                    env.BRANCH = env.GIT_BRANCH?.replaceAll('origin/', '') ?: 'unknown'

                    echo "Building: ${env.APP_NAME}"
                    echo "Branch: ${env.BRANCH}"
                    echo "Commit: ${env.GIT_COMMIT_SHORT}"
                    echo "Image Tag: ${env.IMAGE_TAG}"
                }
            }
        }

        // ============================================
        // Stage 2: Build Application
        // ============================================
        stage('Build') {
            steps {
                container('maven') {
                    dir('spring-boot-app') {
                        echo 'Compiling application...'
                        sh 'mvn clean compile -B -DskipTests'
                    }
                }
            }
        }

        // ============================================
        // Stage 3: Test & Quality (Parallel)
        // ============================================
        stage('Test & Quality') {
            when {
                expression { return !params.SKIP_TESTS }
            }
            parallel {
                // Unit Tests
                stage('Unit Tests') {
                    steps {
                        container('maven') {
                            dir('spring-boot-app') {
                                echo 'Running unit tests...'
                                sh 'mvn test -B'
                            }
                        }
                    }
                    post {
                        always {
                            // Publish test results
                            junit allowEmptyResults: true,
                                  testResults: '**/target/surefire-reports/*.xml'
                        }
                    }
                }

                // Code Coverage
                stage('Code Coverage') {
                    steps {
                        container('maven') {
                            dir('spring-boot-app') {
                                echo 'Generating coverage report...'
                                sh 'mvn jacoco:report -B'
                            }
                        }
                    }
                    post {
                        always {
                            // Publish coverage report
                            publishHTML([
                                allowMissing: true,
                                alwaysLinkToLastBuild: true,
                                keepAll: true,
                                reportDir: 'spring-boot-app/target/site/jacoco',
                                reportFiles: 'index.html',
                                reportName: 'JaCoCo Coverage Report'
                            ])
                        }
                    }
                }
            }
        }

        // ============================================
        // Stage 4: Package Application
        // ============================================
        stage('Package') {
            steps {
                container('maven') {
                    dir('spring-boot-app') {
                        echo 'Packaging application...'
                        sh 'mvn package -B -DskipTests'
                    }
                }
            }
            post {
                success {
                    // Archive the JAR file
                    archiveArtifacts artifacts: 'spring-boot-app/target/*.jar',
                                     fingerprint: true
                }
            }
        }

        // ============================================
        // Stage 5: Build & Push Docker Image
        // ============================================
        stage('Docker Build') {
            steps {
                container('kaniko') {
                    dir('spring-boot-app') {
                        echo "Building Docker image: ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}"

                        sh """
                            /kaniko/executor \
                                --context=. \
                                --dockerfile=Dockerfile \
                                --destination=${env.DOCKER_IMAGE}:${env.IMAGE_TAG} \
                                --destination=${env.DOCKER_IMAGE}:latest \
                                --cache=true \
                                --cache-ttl=24h \
                                --snapshotMode=redo \
                                --use-new-run
                        """
                    }
                }
            }
        }

        // ============================================
        // Stage 6: Deploy to Staging
        // ============================================
        stage('Deploy to Staging') {
            when {
                anyOf {
                    branch 'develop'
                    branch 'feature/*'
                }
            }
            steps {
                container('kubectl') {
                    echo "Deploying to staging namespace: ${env.K8S_NAMESPACE_STAGING}"

                    sh """
                        # Update deployment with new image
                        kubectl set image deployment/${env.APP_NAME} \
                            ${env.APP_NAME}=${env.DOCKER_IMAGE}:${env.IMAGE_TAG} \
                            -n ${env.K8S_NAMESPACE_STAGING} \
                            --record || echo "Deployment not found, skipping..."

                        # Wait for rollout
                        kubectl rollout status deployment/${env.APP_NAME} \
                            -n ${env.K8S_NAMESPACE_STAGING} \
                            --timeout=300s || echo "Rollout status check skipped"
                    """
                }
            }
        }

        // ============================================
        // Stage 7: Deploy to Production
        // ============================================
        stage('Deploy to Production') {
            when {
                allOf {
                    branch 'main'
                    expression { return params.DEPLOY_TO_PROD }
                }
            }
            steps {
                // Manual approval gate
                script {
                    def userInput = input(
                        id: 'deployToProd',
                        message: 'Deploy to Production?',
                        parameters: [
                            choice(
                                choices: ['Proceed', 'Abort'],
                                description: 'Choose action',
                                name: 'ACTION'
                            )
                        ]
                    )

                    if (userInput != 'Proceed') {
                        error('Deployment aborted by user')
                    }
                }

                container('kubectl') {
                    echo "Deploying to production namespace: ${env.K8S_NAMESPACE_PROD}"

                    sh """
                        # Update deployment with new image
                        kubectl set image deployment/${env.APP_NAME} \
                            ${env.APP_NAME}=${env.DOCKER_IMAGE}:${env.IMAGE_TAG} \
                            -n ${env.K8S_NAMESPACE_PROD} \
                            --record

                        # Wait for rollout to complete
                        kubectl rollout status deployment/${env.APP_NAME} \
                            -n ${env.K8S_NAMESPACE_PROD} \
                            --timeout=300s
                    """
                }
            }
        }
    }

    // ============================================
    // Post-build Actions
    // ============================================
    post {
        always {
            echo 'Cleaning up workspace...'
            cleanWs()
        }

        success {
            echo """
            ========================================
            BUILD SUCCESSFUL
            ========================================
            App: ${env.APP_NAME}
            Version: ${env.APP_VERSION}
            Image: ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}
            Build: #${env.BUILD_NUMBER}
            ========================================
            """

            // Uncomment to enable Slack notifications
            // slackSend(
            //     channel: '#builds',
            //     color: 'good',
            //     message: "SUCCESS: ${env.JOB_NAME} #${env.BUILD_NUMBER}\nImage: ${env.DOCKER_IMAGE}:${env.IMAGE_TAG}"
            // )
        }

        failure {
            echo """
            ========================================
            BUILD FAILED
            ========================================
            App: ${env.APP_NAME}
            Build: #${env.BUILD_NUMBER}
            Check console output for details.
            ========================================
            """

            // Uncomment to enable Slack notifications
            // slackSend(
            //     channel: '#builds',
            //     color: 'danger',
            //     message: "FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER}\n${env.BUILD_URL}"
            // )
        }

        unstable {
            echo 'Build is unstable - check test results'
        }
    }
}
