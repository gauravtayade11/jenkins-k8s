# Jenkins on Kubernetes - Complete CI/CD Setup

A comprehensive guide and production-ready implementation for deploying Jenkins on Kubernetes with security best practices and dynamic pod agents.

## Overview

This project provides everything you need to set up a secure, scalable Jenkins CI/CD platform on Kubernetes:

- **6-Part Blog Series**: Step-by-step documentation for Medium
- **Production-Ready Manifests**: Kubernetes YAML files with security hardening
- **Dynamic Pod Agents**: Auto-scaling build agents
- **Sample Spring Boot App**: Complete example with tests and Dockerfile
- **Complete Pipeline**: Jenkinsfile with best practices

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Kubernetes Cluster                                 │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                        jenkins namespace                                │ │
│  │                                                                         │ │
│  │  ┌─────────────────────┐      ┌──────────────────────────────────────┐ │ │
│  │  │   Jenkins Master    │      │         Dynamic Pod Agents           │ │ │
│  │  │  ┌───────────────┐  │      │  ┌─────────┐ ┌─────────┐ ┌────────┐ │ │ │
│  │  │  │  Controller   │  │◄────►│  │ Maven   │ │ Node.js │ │ Kaniko │ │ │ │
│  │  │  │    Pod        │  │      │  │  Pod    │ │   Pod   │ │  Pod   │ │ │ │
│  │  │  └───────────────┘  │      │  └─────────┘ └─────────┘ └────────┘ │ │ │
│  │  └─────────────────────┘      └──────────────────────────────────────┘ │ │
│  │                                                                         │ │
│  │  Security: RBAC | Network Policies | Pod Security Standards            │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Quick Start

### Prerequisites

- Kubernetes cluster (v1.25+)
- kubectl configured
- Helm 3.x (optional)

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/jenkins-k8s-blog.git
cd jenkins-k8s-blog

# Run the setup script
chmod +x scripts/setup-all.sh
./scripts/setup-all.sh

# Or apply manifests manually
kubectl apply -f k8s/namespace/
kubectl apply -f k8s/rbac/
kubectl apply -f k8s/network-policies/
kubectl apply -f k8s/jenkins/

# Create admin secret
kubectl create secret generic jenkins-admin-secret \
  --from-literal=jenkins-admin-user=admin \
  --from-literal=jenkins-admin-password=$(openssl rand -base64 32) \
  -n jenkins

# Access Jenkins
kubectl port-forward -n jenkins svc/jenkins 8080:8080
# Open http://localhost:8080
```

### Verify Installation

```bash
./scripts/verify-setup.sh
```

## Project Structure

```
jenkins-k8s-blog/
├── docs/                           # Blog documentation (6 parts)
│   ├── part-1-introduction/        # Overview and architecture
│   ├── part-2-k8s-setup/          # Namespace, RBAC, Network Policies
│   ├── part-3-jenkins-deployment/ # Jenkins deployment
│   ├── part-4-dynamic-agents/     # Pod templates and agents
│   ├── part-5-spring-boot/        # Sample application
│   └── part-6-pipeline-best-practices/ # Complete pipeline
├── k8s/                            # Kubernetes manifests
│   ├── namespace/                  # Namespace, quotas, limits
│   ├── rbac/                       # Service accounts, roles
│   ├── network-policies/           # Network security
│   ├── jenkins/                    # Jenkins deployment
│   └── pod-templates/              # Agent pod templates
├── spring-boot-app/                # Sample Spring Boot application
│   ├── src/                        # Java source code
│   ├── pom.xml                     # Maven configuration
│   ├── Dockerfile                  # Multi-stage Docker build
│   └── k8s/                        # App deployment manifests
├── scripts/                        # Utility scripts
│   ├── setup-all.sh               # Complete setup
│   ├── verify-setup.sh            # Verification
│   └── cleanup.sh                 # Cleanup resources
├── Jenkinsfile                     # Pipeline definition
├── CONTRIBUTING.md                 # Git flow and contribution guide
└── README.md                       # This file
```

## Blog Series

| Part | Title | Topics |
|------|-------|--------|
| [Part 1](docs/part-1-introduction/README.md) | Introduction & Architecture | Overview, architecture, security pillars |
| [Part 2](docs/part-2-k8s-setup/README.md) | Kubernetes Setup | Namespace, RBAC, Network Policies |
| [Part 3](docs/part-3-jenkins-deployment/README.md) | Jenkins Deployment | Helm/manifests, security configuration |
| [Part 4](docs/part-4-dynamic-agents/README.md) | Dynamic Pod Agents | Pod templates, scaling, optimization |
| [Part 5](docs/part-5-spring-boot/README.md) | Spring Boot Project | Sample app, tests, Dockerfile |
| [Part 6](docs/part-6-pipeline-best-practices/README.md) | Complete Pipeline | Jenkinsfile, best practices |

## Security Features

- **Namespace Isolation**: Dedicated namespace with resource quotas
- **RBAC**: Minimal permissions with service accounts
- **Network Policies**: Zero-trust network model
- **Pod Security Standards**: Restricted security context
- **Non-root Containers**: All containers run as non-root
- **Secrets Management**: Kubernetes secrets for credentials

## Git Flow

This project follows GitFlow branching strategy:

```
main          # Production-ready code
├── develop   # Integration branch
├── feature/* # New features
├── bugfix/*  # Bug fixes
└── release/* # Release preparation
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## Customization

### Update Docker Registry

```yaml
# In Jenkinsfile
environment {
    DOCKER_REGISTRY = 'your-registry.com'
}
```

### Adjust Resource Limits

```yaml
# In k8s/namespace/resource-quota.yaml
spec:
  hard:
    requests.cpu: "16"      # Adjust based on cluster size
    requests.memory: 32Gi
```

### Add Pod Templates

```yaml
# Create new template in k8s/pod-templates/
apiVersion: v1
kind: Pod
metadata:
  labels:
    jenkins/label: agent
    agent-type: custom
# ... your configuration
```

## Troubleshooting

### Pod Won't Start

```bash
kubectl describe pod -n jenkins <pod-name>
kubectl logs -n jenkins <pod-name>
```

### Permission Denied

```bash
kubectl auth can-i create pods --as=system:serviceaccount:jenkins:jenkins -n jenkins
```

### Network Issues

```bash
kubectl get networkpolicies -n jenkins
kubectl describe networkpolicy -n jenkins <policy-name>
```

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'feat: add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Resources

- [Jenkins Documentation](https://www.jenkins.io/doc/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Jenkins Kubernetes Plugin](https://plugins.jenkins.io/kubernetes/)
- [Kaniko](https://github.com/GoogleContainerTools/kaniko)

---

Made with dedication for the DevOps community
