#!/usr/bin/env bash
# Build the jar (with tests unless SKIP_TESTS=true) and the Docker image.
#
#   IMAGE=country-info-service:1.0.0 ./scripts/build.sh
#   SKIP_TESTS=true ./scripts/build.sh
#   LOAD_INTO_MINIKUBE=true ./scripts/build.sh     # make the image visible to minikube
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE="${IMAGE:-country-info-service:1.0.0}"
SKIP_TESTS="${SKIP_TESTS:-false}"
LOAD_INTO_MINIKUBE="${LOAD_INTO_MINIKUBE:-false}"

echo "==> Building jar (skip tests: ${SKIP_TESTS})"
if [[ "${SKIP_TESTS}" == "true" ]]; then
  mvn -B clean package -DskipTests
else
  mvn -B clean verify
fi

echo "==> Building image ${IMAGE}"
docker build -t "${IMAGE}" .

if [[ "${LOAD_INTO_MINIKUBE}" == "true" ]]; then
  echo "==> Loading ${IMAGE} into minikube"
  minikube image load "${IMAGE}"
fi

echo "==> Done: ${IMAGE}"
