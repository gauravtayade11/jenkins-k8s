# Jenkins on Kubernetes: Building a Secure Foundation (Part 2)

*Set up namespaces, RBAC, and network policies that would make your security team proud*

---

Welcome back! In [Part 1](#), we explored why Jenkins on Kubernetes is a game-changer. Now it's time to build the foundation.

Here's the thing about security: **it's much easier to build it in from the start than to bolt it on later**. That's exactly what we're doing today.

By the end of this article, you'll have:
- A dedicated namespace with resource controls
- RBAC policies following least-privilege principles
- Network policies implementing zero-trust security

Let's build something solid.

---

## The Security Layers

Think of our setup like an onion — multiple layers of protection:

```
┌─────────────────────────────────────────────────┐
│              Kubernetes Cluster                  │
│  ┌───────────────────────────────────────────┐  │
│  │           Namespace Isolation              │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │         Network Policies             │  │  │
│  │  │  ┌───────────────────────────────┐  │  │  │
│  │  │  │            RBAC               │  │  │  │
│  │  │  │  ┌─────────────────────────┐  │  │  │  │
│  │  │  │  │    Pod Security         │  │  │  │  │
│  │  │  │  │  ┌───────────────────┐  │  │  │  │  │
│  │  │  │  │  │     Jenkins       │  │  │  │  │  │
│  │  │  │  │  └───────────────────┘  │  │  │  │  │
│  │  │  │  └─────────────────────────┘  │  │  │  │
│  │  │  └───────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

Each layer adds protection. If one fails, others still protect you.

---

## Step 1: Create the Namespace

Namespaces in Kubernetes are like apartments in a building — separate spaces with their own resources and rules.

### Why a Dedicated Namespace?

- **Isolation**: Keep Jenkins separate from other workloads
- **Resource Control**: Prevent Jenkins from consuming all cluster resources
- **Security Boundaries**: Scope RBAC and network policies
- **Easy Cleanup**: Delete the namespace, delete everything

### The Namespace Manifest

```yaml
# k8s/namespace/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: jenkins
  labels:
    name: jenkins
    environment: ci-cd
    # Pod Security Standards - enforcing restricted policy
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

**What's happening here?**

Those `pod-security.kubernetes.io` labels enable Kubernetes' built-in Pod Security Standards. The `restricted` policy is the most secure:
- Containers must run as non-root
- No privilege escalation allowed
- Limited Linux capabilities

Apply it:

```bash
kubectl apply -f k8s/namespace/namespace.yaml
```

---

## Step 2: Resource Quotas

Ever had a runaway build consume all cluster memory? Resource quotas prevent that.

```yaml
# k8s/namespace/resource-quota.yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: jenkins-quota
  namespace: jenkins
spec:
  hard:
    # CPU limits
    requests.cpu: "8"
    limits.cpu: "16"

    # Memory limits
    requests.memory: 16Gi
    limits.memory: 32Gi

    # Storage
    persistentvolumeclaims: "10"

    # Object counts
    pods: "50"
    services: "10"
    secrets: "50"
```

**Translation:**
- Maximum 16 CPU cores across all pods
- Maximum 32GB memory
- Maximum 50 pods (plenty for concurrent builds)
- Maximum 10 PVCs (for caching, workspaces)

### Limit Ranges: Default Limits

What if someone forgets to set resource limits? LimitRange provides defaults:

```yaml
# k8s/namespace/limit-range.yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: jenkins-limits
  namespace: jenkins
spec:
  limits:
    - type: Container
      default:
        cpu: "500m"
        memory: "512Mi"
      defaultRequest:
        cpu: "100m"
        memory: "128Mi"
      max:
        cpu: "4"
        memory: "8Gi"
      min:
        cpu: "50m"
        memory: "64Mi"
```

Now every container gets sensible defaults, and no single container can hog all resources.

Apply both:

```bash
kubectl apply -f k8s/namespace/resource-quota.yaml
kubectl apply -f k8s/namespace/limit-range.yaml
```

---

## Step 3: RBAC — The Principle of Least Privilege

RBAC (Role-Based Access Control) determines what Jenkins can do. The golden rule: **give only the permissions absolutely necessary**.

### Understanding RBAC Components

```
ServiceAccount ──► RoleBinding ──► Role
     │                              │
     │                              ▼
     │                         Permissions
     ▼                         (what can be done)
 Pod/Workload
```

- **ServiceAccount**: Identity for Jenkins
- **Role**: Set of permissions
- **RoleBinding**: Connects ServiceAccount to Role

### Create the Service Account

```yaml
# k8s/rbac/service-account.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins
  namespace: jenkins
  labels:
    app: jenkins
```

### Define the Role

Here's where we're careful. Jenkins needs to:
- Create/delete pods (for dynamic agents)
- Read pod logs (for build output)
- Exec into pods (for running commands)
- Manage ConfigMaps and Secrets

```yaml
# k8s/rbac/role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: jenkins-role
  namespace: jenkins
rules:
  # Pod management - for dynamic agents
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["create", "delete", "get", "list", "watch", "patch"]

  # Pod logs - for build output
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get", "list", "watch"]

  # Pod exec - for running commands in agents
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create", "get"]

  # ConfigMaps and Secrets
  - apiGroups: [""]
    resources: ["configmaps", "secrets"]
    verbs: ["create", "delete", "get", "list", "watch", "update"]

  # PVCs for workspace persistence
  - apiGroups: [""]
    resources: ["persistentvolumeclaims"]
    verbs: ["create", "delete", "get", "list", "watch"]

  # Events for monitoring
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["get", "list", "watch"]
```

**Notice what's NOT here:**
- No access to other namespaces
- No cluster-wide permissions
- No ability to modify deployments, services, etc.

### Bind Role to ServiceAccount

```yaml
# k8s/rbac/role-binding.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-role-binding
  namespace: jenkins
subjects:
  - kind: ServiceAccount
    name: jenkins
    namespace: jenkins
roleRef:
  kind: Role
  name: jenkins-role
  apiGroup: rbac.authorization.k8s.io
```

Apply RBAC:

```bash
kubectl apply -f k8s/rbac/
```

### Verify Permissions

Let's test what Jenkins can and cannot do:

```bash
# Should return "yes"
kubectl auth can-i create pods \
  --as=system:serviceaccount:jenkins:jenkins \
  -n jenkins

# Should return "no"
kubectl auth can-i create deployments \
  --as=system:serviceaccount:jenkins:jenkins \
  -n jenkins

# Should return "no"
kubectl auth can-i get pods \
  --as=system:serviceaccount:jenkins:jenkins \
  -n default
```

---

## Step 4: Network Policies — Zero Trust Networking

Here's a scary thought: if one of your build pods gets compromised, what can it access?

Without network policies: **everything**.

With network policies: **only what we explicitly allow**.

### Default Deny Everything

Start by blocking all traffic:

```yaml
# k8s/network-policies/default-deny.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: jenkins
spec:
  podSelector: {}  # Applies to all pods
  policyTypes:
    - Ingress
    - Egress
  # No rules = deny all
```

Now **nothing** can communicate. Let's open only what's needed.

### Allow DNS

Pods need DNS to resolve hostnames:

```yaml
# k8s/network-policies/allow-dns.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-dns-access
  namespace: jenkins
spec:
  podSelector: {}
  policyTypes:
    - Egress
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: kube-system
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
```

### Allow Jenkins Controller Traffic

The Jenkins controller needs:
- Ingress: Web UI access, agent connections
- Egress: External plugins, agent communication

```yaml
# k8s/network-policies/allow-jenkins-controller.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-jenkins-controller
  namespace: jenkins
spec:
  podSelector:
    matchLabels:
      app: jenkins
      component: controller
  policyTypes:
    - Ingress
    - Egress
  ingress:
    # Web UI access
    - ports:
        - protocol: TCP
          port: 8080
    # Agent connections (JNLP)
    - from:
        - podSelector:
            matchLabels:
              jenkins/label: agent
      ports:
        - protocol: TCP
          port: 50000
  egress:
    # HTTPS to external (plugins, updates)
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except:
              - 10.0.0.0/8
              - 172.16.0.0/12
              - 192.168.0.0/16
      ports:
        - protocol: TCP
          port: 443
    # Communication with agents
    - to:
        - podSelector:
            matchLabels:
              jenkins/label: agent
```

### Allow Agent Traffic

Build agents need:
- Egress: Connect to Jenkins, download dependencies

```yaml
# k8s/network-policies/allow-jenkins-agents.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-jenkins-agents
  namespace: jenkins
spec:
  podSelector:
    matchLabels:
      jenkins/label: agent
  policyTypes:
    - Ingress
    - Egress
  ingress:
    # Only from Jenkins controller
    - from:
        - podSelector:
            matchLabels:
              app: jenkins
              component: controller
  egress:
    # Connect to Jenkins controller
    - to:
        - podSelector:
            matchLabels:
              app: jenkins
      ports:
        - protocol: TCP
          port: 50000
    # HTTPS for dependencies (Maven Central, npm, etc.)
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            except:
              - 10.0.0.0/8
              - 172.16.0.0/12
              - 192.168.0.0/16
      ports:
        - protocol: TCP
          port: 443
        - protocol: TCP
          port: 80
```

Apply network policies:

```bash
kubectl apply -f k8s/network-policies/
```

---

## Verification: Did It Work?

Let's verify everything is in place:

```bash
# Check namespace
kubectl get namespace jenkins

# Check resource quota
kubectl describe resourcequota jenkins-quota -n jenkins

# Check RBAC
kubectl get serviceaccount,role,rolebinding -n jenkins

# Check network policies
kubectl get networkpolicies -n jenkins
```

Or run our verification script:

```bash
./scripts/verify-setup.sh
```

You should see all green checkmarks.

---

## What We've Built

Let's recap our security layers:

| Layer | What It Does | Protection |
|-------|--------------|------------|
| Namespace | Isolation | Separate from other workloads |
| Resource Quota | Limits | Prevents resource exhaustion |
| Pod Security Standards | Container restrictions | Non-root, no escalation |
| RBAC | Permissions | Minimal access only |
| Network Policies | Network isolation | Zero-trust communication |

**This is enterprise-grade security**, and we haven't even deployed Jenkins yet!

---

## Common Issues & Solutions

### "Pods stuck in Pending"
Check resource quota:
```bash
kubectl describe resourcequota jenkins-quota -n jenkins
```

### "Permission denied" errors
Verify RBAC:
```bash
kubectl auth can-i <verb> <resource> \
  --as=system:serviceaccount:jenkins:jenkins \
  -n jenkins
```

### "Connection refused" between pods
Check network policies:
```bash
kubectl describe networkpolicy -n jenkins
```

---

## What's Next?

Our foundation is solid. In **Part 3**, we'll deploy Jenkins itself:

- Helm chart vs raw manifests (pros and cons)
- Persistent storage configuration
- Jenkins Configuration as Code
- Security hardening

The fun part begins!

---

**Previous:** [← Part 1: Introduction & Architecture](#)

**Next:** [Part 3: Jenkins Deployment →](#)

---

*Follow me to get notified when new parts are published!*

---

**Tags:** `Kubernetes` `Security` `RBAC` `NetworkPolicy` `DevOps`
