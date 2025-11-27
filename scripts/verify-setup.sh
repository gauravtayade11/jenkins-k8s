#!/bin/bash
# ============================================
# Verification Script for Jenkins on K8s
# ============================================
# This script verifies all components are correctly deployed.
# ============================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS="${GREEN}[PASS]${NC}"
FAIL="${RED}[FAIL]${NC}"
WARN="${YELLOW}[WARN]${NC}"

echo "============================================"
echo "  Jenkins on K8s - Verification"
echo "============================================"
echo

# Check Namespace
echo "1. Checking Namespace..."
if kubectl get namespace jenkins &> /dev/null; then
    echo -e "   $PASS Namespace 'jenkins' exists"
else
    echo -e "   $FAIL Namespace 'jenkins' not found"
fi

# Check Resource Quota
echo "2. Checking Resource Quota..."
if kubectl get resourcequota jenkins-quota -n jenkins &> /dev/null; then
    echo -e "   $PASS Resource Quota configured"
else
    echo -e "   $WARN Resource Quota not found"
fi

# Check Limit Range
echo "3. Checking Limit Range..."
if kubectl get limitrange jenkins-limits -n jenkins &> /dev/null; then
    echo -e "   $PASS Limit Range configured"
else
    echo -e "   $WARN Limit Range not found"
fi

# Check Service Account
echo "4. Checking Service Account..."
if kubectl get serviceaccount jenkins -n jenkins &> /dev/null; then
    echo -e "   $PASS Service Account 'jenkins' exists"
else
    echo -e "   $FAIL Service Account 'jenkins' not found"
fi

# Check Role
echo "5. Checking RBAC Role..."
if kubectl get role jenkins-role -n jenkins &> /dev/null; then
    echo -e "   $PASS Role 'jenkins-role' exists"
else
    echo -e "   $FAIL Role 'jenkins-role' not found"
fi

# Check RoleBinding
echo "6. Checking RoleBinding..."
if kubectl get rolebinding jenkins-role-binding -n jenkins &> /dev/null; then
    echo -e "   $PASS RoleBinding exists"
else
    echo -e "   $FAIL RoleBinding not found"
fi

# Check Network Policies
echo "7. Checking Network Policies..."
NP_COUNT=$(kubectl get networkpolicies -n jenkins --no-headers 2>/dev/null | wc -l)
if [ "$NP_COUNT" -gt 0 ]; then
    echo -e "   $PASS $NP_COUNT Network Policies configured"
else
    echo -e "   $WARN No Network Policies found"
fi

# Check PVC
echo "8. Checking Persistent Volume Claim..."
PVC_STATUS=$(kubectl get pvc jenkins-pvc -n jenkins -o jsonpath='{.status.phase}' 2>/dev/null)
if [ "$PVC_STATUS" == "Bound" ]; then
    echo -e "   $PASS PVC is Bound"
elif [ -n "$PVC_STATUS" ]; then
    echo -e "   $WARN PVC status: $PVC_STATUS"
else
    echo -e "   $FAIL PVC not found"
fi

# Check Jenkins Deployment
echo "9. Checking Jenkins Deployment..."
READY=$(kubectl get deployment jenkins -n jenkins -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
DESIRED=$(kubectl get deployment jenkins -n jenkins -o jsonpath='{.status.replicas}' 2>/dev/null)
if [ "$READY" == "$DESIRED" ] && [ -n "$READY" ]; then
    echo -e "   $PASS Deployment ready ($READY/$DESIRED)"
elif [ -n "$READY" ]; then
    echo -e "   $WARN Deployment not fully ready ($READY/$DESIRED)"
else
    echo -e "   $FAIL Deployment not found"
fi

# Check Jenkins Pod
echo "10. Checking Jenkins Pod..."
POD_STATUS=$(kubectl get pods -n jenkins -l app=jenkins -o jsonpath='{.items[0].status.phase}' 2>/dev/null)
if [ "$POD_STATUS" == "Running" ]; then
    echo -e "   $PASS Pod is Running"
elif [ -n "$POD_STATUS" ]; then
    echo -e "   $WARN Pod status: $POD_STATUS"
else
    echo -e "   $FAIL No Jenkins pod found"
fi

# Check Services
echo "11. Checking Services..."
if kubectl get service jenkins -n jenkins &> /dev/null; then
    echo -e "   $PASS Jenkins service exists"
else
    echo -e "   $FAIL Jenkins service not found"
fi

# Check Admin Secret
echo "12. Checking Admin Secret..."
if kubectl get secret jenkins-admin-secret -n jenkins &> /dev/null; then
    echo -e "   $PASS Admin secret exists"
else
    echo -e "   $FAIL Admin secret not found"
fi

# Summary
echo
echo "============================================"
echo "  Verification Complete"
echo "============================================"
echo
echo "To access Jenkins:"
echo "  kubectl port-forward -n jenkins svc/jenkins 8080:8080"
echo "  Open: http://localhost:8080"
echo
