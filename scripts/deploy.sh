#!/usr/bin/env bash
# Idempotent deploy to the current kubectl context. Safe to run repeatedly.
#
#   IMAGE=country-info-service:1.0.0 NAMESPACE=country-info ./scripts/deploy.sh
#
# Optional env:
#   INGRESS_HOST           default country-info.local
#   DB_PASSWORD            app DB password for a NEW secret (random if unset)
#   DB_ROOT_PASSWORD       MySQL root password for a NEW secret (random if unset)
#   APPLY_NETWORK_POLICY   true to apply k8s/networkpolicy.yaml (needs a policy-enforcing CNI)
#   ROLLOUT_TIMEOUT        default 300s
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE="${IMAGE:-country-info-service:1.0.0}"
NAMESPACE="${NAMESPACE:-country-info}"
INGRESS_HOST="${INGRESS_HOST:-country-info.local}"
APPLY_NETWORK_POLICY="${APPLY_NETWORK_POLICY:-false}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
K8S_DIR="k8s"
KUBECTL=(kubectl -n "${NAMESPACE}")

log() { printf '\n==> %s\n' "$*"; }

command -v kubectl >/dev/null || { echo "kubectl not found" >&2; exit 1; }
kubectl cluster-info >/dev/null || { echo "cannot reach the cluster (check kubectl context)" >&2; exit 1; }

log "Context: $(kubectl config current-context)  Namespace: ${NAMESPACE}  Image: ${IMAGE}"

log "1/7 Namespace"
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

log "2/7 Secret (created once, never overwritten)"
if "${KUBECTL[@]}" get secret country-info-secrets >/dev/null 2>&1; then
  echo "secret country-info-secrets exists - leaving it unchanged"
else
  gen() { head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }
  "${KUBECTL[@]}" create secret generic country-info-secrets \
    --from-literal=SPRING_DATASOURCE_USERNAME=countryinfo \
    --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD:-$(gen)}" \
    --from-literal=MYSQL_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-$(gen)}"
  echo "secret created"
fi

log "3/7 ConfigMap"
"${KUBECTL[@]}" apply -f "${K8S_DIR}/configmap.yaml"

log "4/7 MySQL"
"${KUBECTL[@]}" apply -f "${K8S_DIR}/mysql-statefulset.yaml"
"${KUBECTL[@]}" rollout status statefulset/mysql --timeout="${ROLLOUT_TIMEOUT}"

log "5/7 Application"
# Render the image tag without mutating the committed manifest.
sed "s|image: country-info-service:.*|image: ${IMAGE}|" "${K8S_DIR}/app-deployment.yaml" \
  | "${KUBECTL[@]}" apply -f -
"${KUBECTL[@]}" apply -f "${K8S_DIR}/service.yaml"
"${KUBECTL[@]}" apply -f "${K8S_DIR}/pdb.yaml"
"${KUBECTL[@]}" apply -f "${K8S_DIR}/hpa.yaml"

log "6/7 Ingress (${INGRESS_HOST})"
sed "s|host: country-info.local|host: ${INGRESS_HOST}|" "${K8S_DIR}/ingress.yaml" \
  | "${KUBECTL[@]}" apply -f -

if [[ "${APPLY_NETWORK_POLICY}" == "true" ]]; then
  "${KUBECTL[@]}" apply -f "${K8S_DIR}/networkpolicy.yaml"
fi

log "7/7 Waiting for rollout"
if ! "${KUBECTL[@]}" rollout status deployment/country-info-service --timeout="${ROLLOUT_TIMEOUT}"; then
  echo "Rollout did not complete. Recent events:" >&2
  "${KUBECTL[@]}" get events --sort-by=.lastTimestamp | tail -n 20 >&2
  echo "Inspect with: kubectl -n ${NAMESPACE} describe pod -l app.kubernetes.io/name=country-info-service" >&2
  echo "Roll back with: NAMESPACE=${NAMESPACE} ./scripts/rollback.sh" >&2
  exit 1
fi

log "Status"
"${KUBECTL[@]}" get pods,svc,ingress,hpa -o wide

log "Access"
if command -v minikube >/dev/null 2>&1 && minikube status >/dev/null 2>&1; then
  echo "minikube ingress: add '$(minikube ip) ${INGRESS_HOST}' to /etc/hosts, then:"
  echo "  curl http://${INGRESS_HOST}/api/v1/countries"
  echo "(On macOS/Windows with the docker driver run 'minikube tunnel' and use 127.0.0.1 instead.)"
else
  echo "Ingress: http://${INGRESS_HOST}/api/v1/countries"
fi
echo "Port-forward alternative:"
echo "  kubectl -n ${NAMESPACE} port-forward svc/country-info-service 8080:80 8081:8081"
echo "  BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh"
