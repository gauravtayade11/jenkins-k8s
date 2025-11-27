# Part 3: Jenkins Deployment with Security

In this part, we'll deploy Jenkins on Kubernetes with security best practices. We'll cover both Helm-based and manifest-based deployment options.

## Prerequisites

- Completed Part 2 (namespace, RBAC, network policies)
- Helm 3.x installed (for Helm deployment)
- Storage class available in your cluster

---

## Deployment Options

| Method | Pros | Cons |
|--------|------|------|
| **Helm Chart** | Easy updates, community maintained | Less control over every detail |
| **Raw Manifests** | Full control, educational | More maintenance |

We'll cover both approaches. Choose based on your needs.

---

## Option 1: Helm Deployment (Recommended)

### Add Jenkins Helm Repository

```bash
helm repo add jenkins https://charts.jenkins.io
helm repo update
```

### Create Values File

```yaml
# k8s/jenkins/helm-values.yaml
controller:
  # Jenkins image configuration
  image: jenkins/jenkins
  tag: "lts-jdk17"
  imagePullPolicy: IfNotPresent

  # Resource allocation
  resources:
    requests:
      cpu: "500m"
      memory: "1Gi"
    limits:
      cpu: "2000m"
      memory: "4Gi"

  # Service Account
  serviceAccount:
    create: false
    name: jenkins

  # Security Context (run as non-root)
  securityContextOverride:
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
    runAsNonRoot: true

  # Container Security Context
  containerSecurityContext:
    runAsUser: 1000
    runAsGroup: 1000
    readOnlyRootFilesystem: false
    allowPrivilegeEscalation: false
    capabilities:
      drop:
        - ALL

  # Pod labels for network policy
  podLabels:
    app: jenkins
    component: controller

  # Jenkins URL (update with your domain)
  jenkinsUrl: "https://jenkins.example.com"

  # Admin user configuration
  admin:
    existingSecret: jenkins-admin-secret
    userKey: jenkins-admin-user
    passwordKey: jenkins-admin-password

  # Install plugins
  installPlugins:
    - kubernetes:latest
    - workflow-aggregator:latest
    - git:latest
    - configuration-as-code:latest
    - job-dsl:latest
    - credentials-binding:latest
    - pipeline-stage-view:latest
    - blueocean:latest
    - prometheus:latest

  # JCasC - Jenkins Configuration as Code
  JCasC:
    defaultConfig: true
    configScripts:
      security: |
        security:
          globalJobDslSecurityConfiguration:
            useScriptSecurity: true
          sSHD:
            port: -1
      cloud: |
        jenkins:
          clouds:
            - kubernetes:
                name: "kubernetes"
                serverUrl: "https://kubernetes.default.svc"
                namespace: "jenkins"
                jenkinsUrl: "http://jenkins:8080"
                jenkinsTunnel: "jenkins-agent:50000"
                containerCapStr: "50"
                podRetention: "onFailure"
                podLabels:
                  - key: "jenkins/label"
                    value: "agent"

  # Service configuration
  serviceType: ClusterIP
  servicePort: 8080

  # Agent listener port
  agentListenerEnabled: true
  agentListenerPort: 50000

  # Health probes
  healthProbes: true
  probes:
    startupProbe:
      httpGet:
        path: '/login'
        port: http
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 30
    livenessProbe:
      httpGet:
        path: '/login'
        port: http
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 5
    readinessProbe:
      httpGet:
        path: '/login'
        port: http
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 3

# Persistence
persistence:
  enabled: true
  storageClass: ""  # Uses default storage class
  size: "20Gi"
  accessMode: ReadWriteOnce

# Agent configuration (we'll customize in Part 4)
agent:
  enabled: true
  image: jenkins/inbound-agent
  tag: "latest-jdk17"
```

### Deploy with Helm

```bash
# Create admin secret first
kubectl create secret generic jenkins-admin-secret \
  --from-literal=jenkins-admin-user=admin \
  --from-literal=jenkins-admin-password=$(openssl rand -base64 32) \
  -n jenkins

# Install Jenkins
helm install jenkins jenkins/jenkins \
  -n jenkins \
  -f k8s/jenkins/helm-values.yaml \
  --wait

# Get admin password
kubectl exec -n jenkins $(kubectl get pods -n jenkins -l app.kubernetes.io/component=jenkins-controller -o jsonpath='{.items[0].metadata.name}') -- cat /run/secrets/additional/chart-admin-password
```

---

## Option 2: Raw Manifest Deployment

For more control, use raw Kubernetes manifests.

### Persistent Volume Claim

```yaml
# k8s/jenkins/pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pvc
  namespace: jenkins
  labels:
    app: jenkins
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 20Gi
  # Uncomment and specify if not using default storage class
  # storageClassName: standard
```

### ConfigMap for Jenkins Configuration

```yaml
# k8s/jenkins/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: jenkins-config
  namespace: jenkins
  labels:
    app: jenkins
data:
  # Jenkins Configuration as Code
  jenkins.yaml: |
    jenkins:
      systemMessage: "Jenkins on Kubernetes - Secured"
      numExecutors: 0  # No builds on controller
      mode: EXCLUSIVE

      securityRealm:
        local:
          allowsSignup: false
          users:
            - id: "admin"
              password: "${JENKINS_ADMIN_PASSWORD}"

      authorizationStrategy:
        loggedInUsersCanDoAnything:
          allowAnonymousRead: false

      clouds:
        - kubernetes:
            name: "kubernetes"
            serverUrl: "https://kubernetes.default.svc"
            namespace: "jenkins"
            jenkinsUrl: "http://jenkins.jenkins.svc.cluster.local:8080"
            jenkinsTunnel: "jenkins-agent.jenkins.svc.cluster.local:50000"
            containerCapStr: "50"
            maxRequestsPerHostStr: "32"
            podRetention: onFailure
            podLabels:
              - key: "jenkins/label"
                value: "agent"

    security:
      globalJobDslSecurityConfiguration:
        useScriptSecurity: true
      sSHD:
        port: -1

    unclassified:
      location:
        url: "https://jenkins.example.com/"
```

### Jenkins Deployment

```yaml
# k8s/jenkins/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: jenkins
  labels:
    app: jenkins
    component: controller
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins
      component: controller
  strategy:
    type: Recreate  # Required for RWO PVC
  template:
    metadata:
      labels:
        app: jenkins
        component: controller
    spec:
      serviceAccountName: jenkins

      # Security Context for Pod
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault

      # Init container to fix permissions
      initContainers:
        - name: init-permissions
          image: busybox:1.36
          command: ['sh', '-c', 'chown -R 1000:1000 /var/jenkins_home']
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
          securityContext:
            runAsUser: 0
            allowPrivilegeEscalation: false

      containers:
        - name: jenkins
          image: jenkins/jenkins:lts-jdk17
          imagePullPolicy: IfNotPresent

          # Container Security Context
          securityContext:
            runAsUser: 1000
            runAsGroup: 1000
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: false
            capabilities:
              drop:
                - ALL

          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
            - name: jnlp
              containerPort: 50000
              protocol: TCP

          # Environment variables
          env:
            - name: JAVA_OPTS
              value: >-
                -Xmx2g
                -Xms512m
                -Djenkins.install.runSetupWizard=false
                -Dhudson.security.csrf.GlobalCrumbIssuerConfiguration.DISABLE_CSRF_PROTECTION=false
            - name: JENKINS_OPTS
              value: "--httpPort=8080"
            - name: CASC_JENKINS_CONFIG
              value: /var/jenkins_config/jenkins.yaml
            - name: JENKINS_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: jenkins-admin-secret
                  key: jenkins-admin-password

          # Resource limits
          resources:
            requests:
              cpu: "500m"
              memory: "1Gi"
            limits:
              cpu: "2000m"
              memory: "4Gi"

          # Volume mounts
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
            - name: jenkins-config
              mountPath: /var/jenkins_config
              readOnly: true

          # Health probes
          startupProbe:
            httpGet:
              path: /login
              port: http
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 30

          livenessProbe:
            httpGet:
              path: /login
              port: http
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 5

          readinessProbe:
            httpGet:
              path: /login
              port: http
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3

      volumes:
        - name: jenkins-home
          persistentVolumeClaim:
            claimName: jenkins-pvc
        - name: jenkins-config
          configMap:
            name: jenkins-config
```

### Services

```yaml
# k8s/jenkins/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: jenkins
  namespace: jenkins
  labels:
    app: jenkins
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
      targetPort: http
      protocol: TCP
    - name: jnlp
      port: 50000
      targetPort: jnlp
      protocol: TCP
  selector:
    app: jenkins
    component: controller
---
# Headless service for agent discovery
apiVersion: v1
kind: Service
metadata:
  name: jenkins-agent
  namespace: jenkins
  labels:
    app: jenkins
spec:
  type: ClusterIP
  clusterIP: None
  ports:
    - name: jnlp
      port: 50000
      targetPort: jnlp
  selector:
    app: jenkins
    component: controller
```

### Ingress (Optional)

```yaml
# k8s/jenkins/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jenkins-ingress
  namespace: jenkins
  labels:
    app: jenkins
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - jenkins.example.com
      secretName: jenkins-tls
  rules:
    - host: jenkins.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: jenkins
                port:
                  number: 8080
```

---

## Applying Manifests

### Deploy Everything

```bash
# Create admin secret
kubectl create secret generic jenkins-admin-secret \
  --from-literal=jenkins-admin-password=$(openssl rand -base64 32) \
  -n jenkins

# Apply manifests in order
kubectl apply -f k8s/jenkins/pvc.yaml
kubectl apply -f k8s/jenkins/configmap.yaml
kubectl apply -f k8s/jenkins/deployment.yaml
kubectl apply -f k8s/jenkins/service.yaml

# Optional: Apply ingress
kubectl apply -f k8s/jenkins/ingress.yaml
```

### Verify Deployment

```bash
# Check pod status
kubectl get pods -n jenkins -w

# Check logs
kubectl logs -f -n jenkins $(kubectl get pods -n jenkins -l app=jenkins -o jsonpath='{.items[0].metadata.name}')

# Get initial admin password (if setup wizard enabled)
kubectl exec -n jenkins $(kubectl get pods -n jenkins -l app=jenkins -o jsonpath='{.items[0].metadata.name}') -- cat /var/jenkins_home/secrets/initialAdminPassword

# Port forward for local access
kubectl port-forward -n jenkins svc/jenkins 8080:8080
```

---

## Security Hardening

### 1. Disable Script Console for Non-Admins

Add to JCasC configuration:

```yaml
jenkins:
  authorizationStrategy:
    projectMatrix:
      permissions:
        - "Overall/Administer:admin"
        - "Overall/Read:authenticated"
```

### 2. Enable Audit Logging

```yaml
unclassified:
  audit-trail:
    logBuildCause: true
    pattern: ".*/(?:configSubmit|doDelete|postBuildResult|cancelQueue).*"
```

### 3. Configure CSRF Protection

Already enabled by default. Ensure this is NOT disabled:

```bash
-Dhudson.security.csrf.GlobalCrumbIssuerConfiguration.DISABLE_CSRF_PROTECTION=false
```

### 4. Secure Agent Communication

```yaml
jenkins:
  slaveAgentPort: 50000
  agentProtocols:
    - "JNLP4-connect"
```

---

## Verification Checklist

- [ ] Jenkins pod is running
- [ ] PVC is bound
- [ ] Service is accessible
- [ ] Admin can log in
- [ ] CSRF protection is enabled
- [ ] Security realm configured
- [ ] No builds on controller (numExecutors: 0)

---

## Troubleshooting

### Pod Stuck in Pending

```bash
# Check events
kubectl describe pod -n jenkins <pod-name>

# Check PVC status
kubectl get pvc -n jenkins
```

### Permission Denied Errors

```bash
# Check init container logs
kubectl logs -n jenkins <pod-name> -c init-permissions

# Verify service account
kubectl get sa jenkins -n jenkins
```

### Plugin Installation Fails

```bash
# Check connectivity
kubectl exec -n jenkins <pod-name> -- curl -I https://updates.jenkins.io
```

---

## Next Part

In **Part 4**, we'll configure dynamic pod agents:
- Pod templates for different build types
- Resource optimization
- Caching strategies

[← Back to Part 2](../part-2-k8s-setup/README.md) | [Continue to Part 4: Dynamic Pod Agents →](../part-4-dynamic-agents/README.md)

---

*This is Part 3 of a 6-part series on deploying Jenkins on Kubernetes with security best practices.*
