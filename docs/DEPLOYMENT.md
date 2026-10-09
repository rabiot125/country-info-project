# Deployment

Target: minikube. Notes for kind and managed clusters are at the end.

## 1. Prerequisites

- Docker, kubectl ≥ 1.27, minikube ≥ 1.32 (or kind), JDK 21 + Maven 3.9 for building
- Cluster add-ons:

```bash
minikube start --cpus=4 --memory=6g          # add --cni=calico to enforce NetworkPolicy
minikube addons enable ingress                # nginx ingress controller
minikube addons enable metrics-server         # required by the HPA
```

## 2. Build the image

```bash
./scripts/build.sh                                  # mvn clean verify + docker build
IMAGE=country-info-service:1.0.0 LOAD_INTO_MINIKUBE=true SKIP_TESTS=true ./scripts/build.sh
```

`minikube image load` copies the image into the cluster's runtime, so no registry is needed.
Alternatively build straight into minikube's Docker: `eval $(minikube docker-env) && docker build -t country-info-service:1.0.0 .`

For a registry:

```bash
docker tag country-info-service:1.0.0 registry.example.com/team/country-info-service:1.0.0
docker push registry.example.com/team/country-info-service:1.0.0
IMAGE=registry.example.com/team/country-info-service:1.0.0 ./scripts/deploy.sh
```

## 3. Deploy

```bash
IMAGE=country-info-service:1.0.0 NAMESPACE=country-info ./scripts/deploy.sh
```

The script is idempotent and applies, in order:

1. Namespace
2. Secret `country-info-secrets` — **created once** from `DB_PASSWORD` / `DB_ROOT_PASSWORD` or random values, never
   overwritten (rotating it under an initialised MySQL would lock the app out)
3. ConfigMap `country-info-config`
4. MySQL StatefulSet + headless Service; waits until ready
5. App Deployment (image substituted), Service, PDB, HPA
6. Ingress (host from `INGRESS_HOST`, default `country-info.local`), NetworkPolicy if `APPLY_NETWORK_POLICY=true`
7. Waits for the rollout, prints status and access URL; on failure prints recent events and the rollback command

Manual equivalent:

```bash
kubectl create namespace country-info
kubectl -n country-info create secret generic country-info-secrets \
  --from-literal=SPRING_DATASOURCE_USERNAME=countryinfo \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<app-password>' \
  --from-literal=MYSQL_ROOT_PASSWORD='<root-password>'
kubectl apply -k k8s/
```

## 4. Verify

```bash
kubectl -n country-info get pods,svc,ingress,hpa,pdb
kubectl -n country-info rollout status deploy/country-info-service
kubectl -n country-info logs deploy/country-info-service --tail=50
kubectl -n country-info port-forward svc/country-info-service 8081:8081 &
curl -s localhost:8081/actuator/health/readiness        # {"status":"UP"}
curl -s localhost:8081/actuator/prometheus | grep soap_client
```

## 5. Access

**Ingress**

```bash
echo "$(minikube ip) country-info.local" | sudo tee -a /etc/hosts
curl http://country-info.local/api/v1/countries
BASE_URL=http://country-info.local ./scripts/smoke-test.sh
```

With the docker driver on macOS/Windows run `minikube tunnel` and map `127.0.0.1 country-info.local` instead.

**Port-forward**

```bash
kubectl -n country-info port-forward svc/country-info-service 8080:80 8081:8081
BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh
open http://localhost:8080/swagger-ui.html     # dev profile only; disabled in prod
```

## 6. Scale

```bash
kubectl -n country-info get hpa country-info-service -w
kubectl -n country-info scale deploy/country-info-service --replicas=4   # HPA will reconcile back within its bounds
# generate load to watch the HPA react
kubectl -n country-info run load --rm -it --image=busybox:1.36 --restart=Never -- \
  sh -c 'while true; do wget -q -O- http://country-info-service/api/v1/countries >/dev/null; done'
```

## 7. Upgrade

```bash
IMAGE=country-info-service:1.1.0 LOAD_INTO_MINIKUBE=true ./scripts/build.sh
IMAGE=country-info-service:1.1.0 ./scripts/deploy.sh
kubectl -n country-info rollout history deploy/country-info-service
```

Rolling update with `maxUnavailable: 0`, `maxSurge: 1`: a new pod must pass readiness before an old one is removed;
old pods get a 5 s preStop delay then up to 20 s graceful shutdown. Flyway migrations run on startup — keep them
backward compatible (expand → migrate → contract) so old and new pods can share the schema during the rollout.

## 8. Roll back

```bash
NAMESPACE=country-info ./scripts/rollback.sh                 # previous revision
NAMESPACE=country-info TO_REVISION=2 ./scripts/rollback.sh
```

Rollback does not undo Flyway migrations; that is why migrations must stay backward compatible.

## 9. Clean up

```bash
kubectl delete namespace country-info          # removes everything including the MySQL PVC
minikube stop   # or: minikube delete
```

## 10. Other clusters

| Cluster | Changes |
|---|---|
| **kind** | `kind load docker-image country-info-service:1.0.0`; install ingress-nginx with the kind manifest and label the node `ingress-ready=true`; install metrics-server with `--kubelet-insecure-tls`; default storage class `standard` works for the PVC. |
| **GKE / EKS / AKS** | Push to a registry (Artifact Registry / ECR / ACR) and set `IMAGE`; set `INGRESS_HOST` to a real DNS name and add TLS (cert-manager); replace the MySQL StatefulSet with Cloud SQL / RDS / Azure Database (change `SPRING_DATASOURCE_URL`, use SSL, drop `allowPublicKeyRetrieval`); source the Secret from a secret manager (External Secrets / Workload Identity); `whenUnsatisfiable: DoNotSchedule` on the topology spread for strict multi-zone spreading (`topology.kubernetes.io/zone`). |
