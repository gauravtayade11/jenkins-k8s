#!/bin/bash
# ============================================
# Complete Jenkins on K8s Setup Script
# ============================================
# This script sets up the entire Jenkins on Kubernetes
# infrastructure with security best practices.
# ============================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed"
        exit 1
    fi

    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster"
        exit 1
    fi

    log_info "Prerequisites check passed!"
}

# Create namespace and security resources
setup_namespace() {
    log_info "Setting up namespace and security..."

    kubectl apply -f k8s/namespace/namespace.yaml
    kubectl apply -f k8s/namespace/resource-quota.yaml
    kubectl apply -f k8s/namespace/limit-range.yaml

    log_info "Namespace setup complete!"
}

# Setup RBAC
setup_rbac() {
    log_info "Setting up RBAC..."

    kubectl apply -f k8s/rbac/service-account.yaml
    kubectl apply -f k8s/rbac/role.yaml
    kubectl apply -f k8s/rbac/role-binding.yaml

    # Optional: Cluster role for multi-namespace deployments
    read -p "Setup ClusterRole for multi-namespace access? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        kubectl apply -f k8s/rbac/cluster-role.yaml
        log_info "ClusterRole applied"
    fi

    log_info "RBAC setup complete!"
}

# Setup Network Policies
setup_network_policies() {
    log_info "Setting up Network Policies..."

    kubectl apply -f k8s/network-policies/default-deny.yaml
    kubectl apply -f k8s/network-policies/allow-dns.yaml
    kubectl apply -f k8s/network-policies/allow-jenkins-controller.yaml
    kubectl apply -f k8s/network-policies/allow-jenkins-agents.yaml

    log_info "Network Policies setup complete!"
}

# Create Jenkins admin secret
create_admin_secret() {
    log_info "Creating Jenkins admin secret..."

    # Check if secret exists
    if kubectl get secret jenkins-admin-secret -n jenkins &> /dev/null; then
        log_warn "Secret already exists, skipping..."
        return
    fi

    # Generate random password
    ADMIN_PASSWORD=$(openssl rand -base64 32)

    kubectl create secret generic jenkins-admin-secret \
        --from-literal=jenkins-admin-user=admin \
        --from-literal=jenkins-admin-password="${ADMIN_PASSWORD}" \
        -n jenkins

    log_info "Admin secret created!"
    log_warn "Admin password: ${ADMIN_PASSWORD}"
    log_warn "Save this password securely!"
}

# Deploy Jenkins
deploy_jenkins() {
    log_info "Deploying Jenkins..."

    kubectl apply -f k8s/jenkins/pvc.yaml
    kubectl apply -f k8s/jenkins/configmap.yaml
    kubectl apply -f k8s/jenkins/deployment.yaml
    kubectl apply -f k8s/jenkins/service.yaml

    log_info "Jenkins deployment initiated!"
    log_info "Waiting for Jenkins pod to be ready..."

    kubectl wait --for=condition=ready pod -l app=jenkins -n jenkins --timeout=300s

    log_info "Jenkins is ready!"
}

# Main execution
main() {
    echo "============================================"
    echo "  Jenkins on Kubernetes Setup Script"
    echo "============================================"
    echo

    check_prerequisites

    echo
    log_info "Starting setup..."
    echo

    setup_namespace
    setup_rbac
    setup_network_policies
    create_admin_secret
    deploy_jenkins

    echo
    echo "============================================"
    echo "  Setup Complete!"
    echo "============================================"
    echo
    log_info "Access Jenkins using:"
    echo "  kubectl port-forward -n jenkins svc/jenkins 8080:8080"
    echo "  Then open: http://localhost:8080"
    echo
    log_info "Get admin password:"
    echo "  kubectl get secret jenkins-admin-secret -n jenkins -o jsonpath='{.data.jenkins-admin-password}' | base64 -d"
    echo
}

# Run main
main "$@"
