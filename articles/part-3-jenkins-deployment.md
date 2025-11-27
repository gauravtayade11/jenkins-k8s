# Jenkins on Kubernetes: Deploying Jenkins the Right Way (Part 3)

*Helm charts, persistent storage, and security hardening for production*

---

We've built a secure foundation in [Part 2](#). Now comes the exciting part — deploying Jenkins itself.

But here's the thing: deploying Jenkins on Kubernetes isn't just `kubectl apply`. There are **critical decisions** that will determine whether your Jenkins is production-ready or a ticking time bomb.

In this article, we'll:
- Compare Helm vs raw manifests (and when to use each)
- Configure persistent storage properly
- Set up Jenkins Configuration as Code
- Harden security at the application level

Let's deploy Jenkins the right way.

---

## Deployment Options: Helm vs Raw Manifests

You have two paths:

| Approach | Pros | Cons |
|----------|------|------|
| **Helm Chart** | Easy updates, community maintained, sensible defaults | Less control over details |
| **Raw Manifests** | Full control, educational, customizable | More maintenance |

**My recommendation:** Start with Helm for production, use manifests for learning.

We'll cover both.

---

## Option 1: Helm Deployment (Recommended)

### Add the Jenkins Helm Repository

```bash
helm repo add jenkins https://charts.jenkins.io
helm repo update
```

### Create Values File

This is where the magic happens. Our values file configures everything:

```yaml
# k8s/jenkins/helm-values.yaml
controller:
  image:
    tag: "lts-jdk17"

  # Resources
  resources:
    requests:
      cpu: "500m"
      memory: "1Gi"
    limits:
      cpu: "2000m"
      memory: "4Gi"

  # Use our existing service account
  serviceAccount:
    create: false
    name: jenkins

  # Security Context - run as non-root
  podSecurityContextOverride:
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
    runAsNonRoot: true

  containerSecurityContext:
    runAsUser: 1000
    runAsGroup: 1000
    allowPrivilegeEscalation: false
    capabilities:
      drop:
        - ALL

  # Labels for network policy
  podLabels:
    app: jenkins
    component: controller

  # Essential plugins
  installPlugins:
    - kubernetes:latest
    - workflow-aggregator:latest
    - git:latest
    - configuration-as-code:latest
    - credentials-binding:latest
    - blueocean:latest

  # Jenkins Configuration as Code
  JCasC:
    defaultConfig: true
    configScripts:
      security: |
        jenkins:
          numExecutors: 0  # No builds on controller!
          mode: EXCLUSIVE

persistence:
  enabled: true
  size: "20Gi"
```

**Key settings explained:**

- `numExecutors: 0` — **Critical!** Never run builds on the controller. It's a security risk and performance bottleneck.
- `runAsNonRoot: true` — Containers run as non-root user
- `allowPrivilegeEscalation: false` — Prevents container escapes

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
```

The `--wait` flag ensures Helm waits until Jenkins is actually running.

### Get the Admin Password

```bash
kubectl get secret jenkins-admin-secret -n jenkins \
  -o jsonpath='{.data.jenkins-admin-password}' | base64 -d
```

---

## Option 2: Raw Manifest Deployment

For those who want full control (or want to learn), here's the manifest approach.

### Persistent Volume Claim

Jenkins needs persistent storage for:
- Job configurations
- Build history
- Plugin data

```yaml
# k8s/jenkins/pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pvc
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 20Gi
```

### ConfigMap with JCasC

Jenkins Configuration as Code (JCasC) lets us configure Jenkins declaratively:

```yaml
# k8s/jenkins/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: jenkins-config
  namespace: jenkins
data:
  jenkins.yaml: |
    jenkins:
      systemMessage: "Jenkins on Kubernetes - Production Ready"
      numExecutors: 0
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
            jenkinsUrl: "http://jenkins:8080"
            jenkinsTunnel: "jenkins-agent:50000"
            containerCapStr: "50"
            podRetention: onFailure
```

### The Deployment

```yaml
# k8s/jenkins/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: jenkins
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

      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true

      initContainers:
        - name: init-permissions
          image: busybox:1.36
          command: ['sh', '-c', 'chown -R 1000:1000 /var/jenkins_home']
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
          securityContext:
            runAsUser: 0

      containers:
        - name: jenkins
          image: jenkins/jenkins:lts-jdk17

          securityContext:
            runAsUser: 1000
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL

          ports:
            - name: http
              containerPort: 8080
            - name: jnlp
              containerPort: 50000

          env:
            - name: JAVA_OPTS
              value: "-Xmx2g -Djenkins.install.runSetupWizard=false"
            - name: CASC_JENKINS_CONFIG
              value: /var/jenkins_config/jenkins.yaml
            - name: JENKINS_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: jenkins-admin-secret
                  key: jenkins-admin-password

          resources:
            requests:
              cpu: "500m"
              memory: "1Gi"
            limits:
              cpu: "2000m"
              memory: "4Gi"

          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
            - name: jenkins-config
              mountPath: /var/jenkins_config

          livenessProbe:
            httpGet:
              path: /login
              port: http
            initialDelaySeconds: 60
            periodSeconds: 10

          readinessProbe:
            httpGet:
              path: /login
              port: http
            initialDelaySeconds: 60
            periodSeconds: 10

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
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
    - name: jnlp
      port: 50000
  selector:
    app: jenkins
    component: controller
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins-agent
  namespace: jenkins
spec:
  type: ClusterIP
  clusterIP: None  # Headless service
  ports:
    - name: jnlp
      port: 50000
  selector:
    app: jenkins
    component: controller
```

### Deploy Everything

```bash
kubectl apply -f k8s/jenkins/pvc.yaml
kubectl apply -f k8s/jenkins/configmap.yaml
kubectl apply -f k8s/jenkins/deployment.yaml
kubectl apply -f k8s/jenkins/service.yaml
```

---

## Accessing Jenkins

### Port Forward (Development)

```bash
kubectl port-forward -n jenkins svc/jenkins 8080:8080
```

Open http://localhost:8080

### Ingress (Production)

```yaml
# k8s/jenkins/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jenkins-ingress
  namespace: jenkins
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
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

## Security Hardening Checklist

Let's make sure Jenkins is locked down:

### 1. Disable Setup Wizard
Already done with:
```
-Djenkins.install.runSetupWizard=false
```

### 2. No Builds on Controller
```yaml
jenkins:
  numExecutors: 0
  mode: EXCLUSIVE
```

### 3. CSRF Protection
Enabled by default. **Never disable it:**
```
-Dhudson.security.csrf.GlobalCrumbIssuerConfiguration.DISABLE_CSRF_PROTECTION=false
```

### 4. Agent Protocols
Use only secure JNLP4:
```yaml
jenkins:
  agentProtocols:
    - "JNLP4-connect"
```

### 5. Disable SSH Server
```yaml
security:
  sSHD:
    port: -1
```

---

## Verify the Deployment

```bash
# Check pod status
kubectl get pods -n jenkins

# Check logs
kubectl logs -n jenkins -l app=jenkins -f

# Run verification script
./scripts/verify-setup.sh
```

You should see:
```
[PASS] Namespace 'jenkins' exists
[PASS] PVC is Bound
[PASS] Deployment ready (1/1)
[PASS] Pod is Running
[PASS] Jenkins service exists
```

---

## Troubleshooting

### Pod Stuck in Pending

```bash
kubectl describe pod -n jenkins -l app=jenkins
```

Common causes:
- PVC not binding (check storage class)
- Resource quota exceeded
- Node selector not matching

### Permission Denied

```bash
kubectl logs -n jenkins -l app=jenkins
```

Check if init container ran successfully. May need to adjust fsGroup.

### Plugin Installation Fails

Jenkins needs HTTPS access to download plugins. Check network policy allows egress to 443.

---

## What We've Achieved

Jenkins is now running with:

| Feature | Status |
|---------|--------|
| Persistent Storage | 20GB PVC |
| Configuration as Code | JCasC enabled |
| Security Context | Non-root, no escalation |
| Health Probes | Liveness & Readiness |
| Resource Limits | CPU/Memory bounded |
| Network Isolation | Policy enforced |

But we're not done. Jenkins without agents is like a car without wheels.

---

## What's Next?

In **Part 4**, we'll configure the heart of our system — **dynamic pod agents**:

- Pod templates for Maven, Node.js, Docker builds
- Multi-container pods
- Caching strategies for faster builds
- Scaling and optimization

This is where Kubernetes truly shines for CI/CD.

---

**Previous:** [← Part 2: Kubernetes Setup](#)

**Next:** [Part 4: Dynamic Pod Agents →](#)

---

*Follow me to get notified when new parts are published!*

---

**Tags:** `Jenkins` `Kubernetes` `Helm` `DevOps` `CI/CD`
