# Part 2: Kubernetes Setup & Namespace Security

In this part, we'll set up the Kubernetes foundation with security best practices. We'll create a dedicated namespace, configure RBAC, implement Network Policies, and set resource quotas.

## Prerequisites

- Kubernetes cluster running (v1.25+)
- `kubectl` configured and connected
- Cluster-admin access

---

## 1. Namespace Configuration

### Why Dedicated Namespace?

A dedicated namespace provides:
- **Isolation**: Separate Jenkins resources from other workloads
- **Resource Management**: Apply quotas and limits
- **Security Boundaries**: Scope RBAC policies
- **Easy Cleanup**: Delete everything by removing the namespace

### Create the Namespace

```yaml
# k8s/namespace/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: jenkins
  labels:
    name: jenkins
    environment: ci-cd
    # Enable Pod Security Standards
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

```bash
kubectl apply -f k8s/namespace/namespace.yaml
```

### Resource Quota

Prevent resource exhaustion by limiting the namespace:

```yaml
# k8s/namespace/resource-quota.yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: jenkins-quota
  namespace: jenkins
spec:
  hard:
    requests.cpu: "8"
    requests.memory: 16Gi
    limits.cpu: "16"
    limits.memory: 32Gi
    persistentvolumeclaims: "10"
    pods: "50"
    services: "10"
    secrets: "50"
    configmaps: "50"
```

### Limit Range

Set default resource limits for pods:

```yaml
# k8s/namespace/limit-range.yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: jenkins-limits
  namespace: jenkins
spec:
  limits:
    - default:
        cpu: "500m"
        memory: "512Mi"
      defaultRequest:
        cpu: "100m"
        memory: "128Mi"
      type: Container
    - max:
        cpu: "4"
        memory: "8Gi"
      min:
        cpu: "50m"
        memory: "64Mi"
      type: Container
```

---

## 2. RBAC Configuration

### Understanding RBAC

RBAC (Role-Based Access Control) controls what actions can be performed:

```
┌─────────────────────────────────────────────────────────────┐
│                    RBAC Components                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ServiceAccount ──► RoleBinding ──► Role                    │
│       │                              │                      │
│       │                              ▼                      │
│       │                         Permissions                 │
│       │                         (verbs on resources)        │
│       ▼                                                     │
│    Pod/Workload                                             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Service Account for Jenkins

```yaml
# k8s/rbac/service-account.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins
  namespace: jenkins
  labels:
    app: jenkins
automountServiceAccountToken: true
```

### Jenkins Role (Namespace-scoped)

```yaml
# k8s/rbac/role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: jenkins-role
  namespace: jenkins
rules:
  # Pod management for dynamic agents
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["create", "delete", "get", "list", "watch", "patch"]

  # Pod logs for build output
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get", "list", "watch"]

  # Execute commands in pods
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create", "get"]

  # ConfigMaps for configuration
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["create", "delete", "get", "list", "watch", "update"]

  # Secrets for credentials
  - apiGroups: [""]
    resources: ["secrets"]
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

### Role Binding

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

### Optional: ClusterRole for Multi-Namespace Deployments

If Jenkins needs to deploy to other namespaces:

```yaml
# k8s/rbac/cluster-role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-cluster-role
rules:
  # Read-only access to namespaces
  - apiGroups: [""]
    resources: ["namespaces"]
    verbs: ["get", "list", "watch"]

  # Deployment capabilities (if needed)
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["create", "delete", "get", "list", "watch", "update", "patch"]

  # Service management
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["create", "delete", "get", "list", "watch", "update"]
```

---

## 3. Network Policies

### Why Network Policies?

Network Policies implement a **zero-trust** network model:
- Default deny all traffic
- Explicitly allow only required communication
- Prevent lateral movement in case of breach

### Default Deny All

```yaml
# k8s/network-policies/default-deny.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: jenkins
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
```

### Allow Jenkins Controller Traffic

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
    # Allow traffic from ingress controller
    - from:
        - namespaceSelector:
            matchLabels:
              name: ingress-nginx
      ports:
        - protocol: TCP
          port: 8080
    # Allow traffic from agents
    - from:
        - podSelector:
            matchLabels:
              jenkins/label: agent
      ports:
        - protocol: TCP
          port: 50000
  egress:
    # DNS resolution
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
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
    # Allow from Jenkins controller
    - from:
        - podSelector:
            matchLabels:
              app: jenkins
              component: controller
  egress:
    # DNS resolution
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    # Connect to Jenkins controller
    - to:
        - podSelector:
            matchLabels:
              app: jenkins
              component: controller
      ports:
        - protocol: TCP
          port: 50000
    # HTTPS for downloading dependencies
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

---

## 4. Applying the Configuration

### Deploy All Resources

```bash
# Create namespace first
kubectl apply -f k8s/namespace/namespace.yaml

# Apply resource controls
kubectl apply -f k8s/namespace/resource-quota.yaml
kubectl apply -f k8s/namespace/limit-range.yaml

# Apply RBAC
kubectl apply -f k8s/rbac/

# Apply Network Policies
kubectl apply -f k8s/network-policies/
```

### Verify Configuration

```bash
# Check namespace
kubectl get namespace jenkins -o yaml

# Check resource quota
kubectl describe resourcequota jenkins-quota -n jenkins

# Check limit range
kubectl describe limitrange jenkins-limits -n jenkins

# Check RBAC
kubectl get serviceaccount,role,rolebinding -n jenkins

# Check network policies
kubectl get networkpolicies -n jenkins
```

---

## 5. Verification Script

Save this script to verify your setup:

```bash
#!/bin/bash
# scripts/verify-k8s-setup.sh

echo "=== Verifying Kubernetes Setup ==="

echo -e "\n1. Checking Namespace..."
kubectl get namespace jenkins || echo "ERROR: Namespace not found"

echo -e "\n2. Checking Resource Quota..."
kubectl get resourcequota -n jenkins || echo "ERROR: No resource quota"

echo -e "\n3. Checking Limit Range..."
kubectl get limitrange -n jenkins || echo "ERROR: No limit range"

echo -e "\n4. Checking Service Account..."
kubectl get serviceaccount jenkins -n jenkins || echo "ERROR: Service account not found"

echo -e "\n5. Checking Role..."
kubectl get role jenkins-role -n jenkins || echo "ERROR: Role not found"

echo -e "\n6. Checking RoleBinding..."
kubectl get rolebinding jenkins-role-binding -n jenkins || echo "ERROR: RoleBinding not found"

echo -e "\n7. Checking Network Policies..."
kubectl get networkpolicies -n jenkins || echo "WARNING: No network policies"

echo -e "\n=== Setup Verification Complete ==="
```

---

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| Pods stuck in Pending | Check resource quota limits |
| Permission denied errors | Verify RBAC configuration |
| Pod-to-pod communication fails | Check Network Policy rules |
| DNS resolution fails | Ensure DNS egress is allowed |

### Debug Commands

```bash
# Check pod events
kubectl describe pod <pod-name> -n jenkins

# Test service account permissions
kubectl auth can-i create pods --as=system:serviceaccount:jenkins:jenkins -n jenkins

# Test network connectivity
kubectl run test-pod --rm -it --image=busybox -n jenkins -- wget -O- http://jenkins:8080
```

---

## Security Checklist

- [ ] Namespace created with Pod Security Standards
- [ ] Resource Quota configured
- [ ] Limit Range configured
- [ ] Service Account created (no auto-mount in other pods)
- [ ] Role with minimal permissions
- [ ] RoleBinding connects SA to Role
- [ ] Default deny Network Policy
- [ ] Explicit allow policies for required traffic

---

## Next Part

In **Part 3**, we'll deploy Jenkins itself:
- Helm chart configuration
- Persistent storage setup
- Security configuration
- Plugin management

[← Back to Part 1](../part-1-introduction/README.md) | [Continue to Part 3: Jenkins Deployment →](../part-3-jenkins-deployment/README.md)

---

*This is Part 2 of a 6-part series on deploying Jenkins on Kubernetes with security best practices.*
