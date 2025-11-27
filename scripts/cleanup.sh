#!/bin/bash
# ============================================
# Cleanup Script for Jenkins on K8s
# ============================================
# WARNING: This will delete all Jenkins resources!
# ============================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${RED}============================================${NC}"
echo -e "${RED}  WARNING: Jenkins Cleanup Script${NC}"
echo -e "${RED}============================================${NC}"
echo
echo "This will DELETE all Jenkins resources including:"
echo "  - Jenkins namespace and all contents"
echo "  - Persistent volumes and data"
echo "  - RBAC resources"
echo "  - ClusterRole (if exists)"
echo
read -p "Are you sure you want to continue? (type 'yes' to confirm): " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    echo "Aborted."
    exit 0
fi

echo
echo -e "${YELLOW}Starting cleanup...${NC}"
echo

# Delete ClusterRole and ClusterRoleBinding (if exists)
echo "Deleting ClusterRole resources..."
kubectl delete clusterrolebinding jenkins-cluster-role-binding --ignore-not-found=true
kubectl delete clusterrole jenkins-cluster-role --ignore-not-found=true

# Delete namespace (this will delete everything in it)
echo "Deleting jenkins namespace..."
kubectl delete namespace jenkins --ignore-not-found=true

echo
echo -e "${GREEN}Cleanup complete!${NC}"
echo
