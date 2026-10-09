#!/usr/bin/env bash
# Roll the app Deployment back to the previous (or a given) revision and verify it.
#
#   NAMESPACE=country-info ./scripts/rollback.sh          # previous revision
#   NAMESPACE=country-info TO_REVISION=3 ./scripts/rollback.sh
set -euo pipefail

NAMESPACE="${NAMESPACE:-country-info}"
DEPLOYMENT="${DEPLOYMENT:-country-info-service}"
TO_REVISION="${TO_REVISION:-}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
KUBECTL=(kubectl -n "${NAMESPACE}")

echo "==> Revision history"
"${KUBECTL[@]}" rollout history "deployment/${DEPLOYMENT}"

echo "==> Rolling back ${DEPLOYMENT}${TO_REVISION:+ to revision ${TO_REVISION}}"
if [[ -n "${TO_REVISION}" ]]; then
  "${KUBECTL[@]}" rollout undo "deployment/${DEPLOYMENT}" --to-revision="${TO_REVISION}"
else
  "${KUBECTL[@]}" rollout undo "deployment/${DEPLOYMENT}"
fi

echo "==> Waiting for rollout"
"${KUBECTL[@]}" rollout status "deployment/${DEPLOYMENT}" --timeout="${ROLLOUT_TIMEOUT}"

echo "==> Verifying"
"${KUBECTL[@]}" get deployment "${DEPLOYMENT}" \
  -o jsonpath='{"image: "}{.spec.template.spec.containers[0].image}{"\nready: "}{.status.readyReplicas}{"/"}{.spec.replicas}{"\n"}'

ready="$("${KUBECTL[@]}" get deployment "${DEPLOYMENT}" -o jsonpath='{.status.readyReplicas}')"
wanted="$("${KUBECTL[@]}" get deployment "${DEPLOYMENT}" -o jsonpath='{.spec.replicas}')"
if [[ "${ready:-0}" != "${wanted}" ]]; then
  echo "Rollback finished but only ${ready:-0}/${wanted} replicas are ready" >&2
  exit 1
fi
echo "==> Rollback complete"
