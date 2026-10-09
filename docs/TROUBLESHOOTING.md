# Troubleshooting

All commands assume `NS=country-info` and `APP=app.kubernetes.io/name=country-info-service`.

```bash
export NS=country-info APP=app.kubernetes.io/name=country-info-service
```

## Core workflow

```bash
kubectl -n $NS get pods -o wide                                  # state, restarts, node
kubectl -n $NS describe pod -l $APP                              # events, probe failures, last state
kubectl -n $NS get events --sort-by=.lastTimestamp | tail -30    # what the cluster did recently
kubectl -n $NS logs deploy/country-info-service --tail=200       # current logs (JSON)
kubectl -n $NS logs <pod> --previous                             # logs of the crashed container
kubectl -n $NS logs -l $APP --prefix --tail=100 | jq -c 'select(.level=="ERROR" or .level=="WARN")'
kubectl -n $NS exec -it <pod> -- sh                              # shell (no curl in the JRE image)
kubectl -n $NS port-forward <pod> 8081:8081                      # reach actuator of one pod
kubectl -n $NS top pods                                          # needs metrics-server
```

## Runbook

| Symptom | Likely cause | Check | Fix |
|---|---|---|---|
| `ImagePullBackOff` / `ErrImagePull` | Image not in the cluster's runtime or wrong tag/registry | `kubectl -n $NS describe pod -l $APP \| grep -A5 Events` | minikube: `minikube image load country-info-service:1.0.0`; kind: `kind load docker-image ...`; registry: check tag and `imagePullSecrets` |
| `CrashLoopBackOff` | App fails at startup: missing env, DB auth, Flyway, bad config | `kubectl -n $NS logs <pod> --previous` | Fix the reported config; `APPLICATION FAILED TO START` lines name the property |
| Pod `Pending` | Not enough CPU/memory, or PVC unbound | `kubectl -n $NS describe pod <pod>` → `FailedScheduling`; `kubectl get pvc -n $NS` | Lower requests or add nodes (`minikube start --cpus 4 --memory 6g`); see PVC row |
| `OOMKilled` (last state) | Heap + metaspace + threads exceed 768Mi | `kubectl -n $NS describe pod <pod> \| grep -A3 'Last State'`; `kubectl top pods` | Raise memory limit, or lower `MaxRAMPercentage`; check for leaks via `/actuator/metrics/jvm.memory.used` |
| Init container stuck `Init:0/1` | MySQL Service not resolving / not ready | `kubectl -n $NS logs <pod> -c wait-for-mysql`; `kubectl -n $NS get pods -l app.kubernetes.io/name=mysql` | Fix MySQL first (rows below) |
| Readiness failing (`0/1 Running`) | DB down or pool exhausted | `kubectl -n $NS port-forward <pod> 8081:8081` then `curl localhost:8081/actuator/health/readiness` | `db` component shows the error; fix DB/credentials |
| Liveness failing → restarts | JVM stalled (GC thrash, deadlock) or probe timeout too tight | `describe pod` → `Liveness probe failed`; `curl :8081/actuator/metrics/jvm.gc.pause` | More memory/CPU; thread dump to the logs: `kubectl -n $NS exec <pod> -- kill -3 1` (the JRE image has no jcmd) |
| Startup probe failing | Startup > 155 s (slow node, big migration) | `describe pod` → `Startup probe failed` | Raise `startupProbe.failureThreshold` |
| `Access denied for user` / `Communications link failure` | Wrong Secret, MySQL not ready, wrong URL | `kubectl -n $NS logs <pod> --previous \| grep -i -E 'access denied\|link failure'`; `kubectl -n $NS get secret country-info-secrets -o jsonpath='{.data.SPRING_DATASOURCE_USERNAME}' \| base64 -d` | Secret must match what MySQL was initialised with. If the PVC was created with old passwords, either restore them or delete the PVC (data loss) |
| MySQL pod `Pending`, PVC `Pending` | No default StorageClass / provisioner | `kubectl get sc`; `kubectl -n $NS describe pvc data-mysql-0` | minikube: `minikube addons enable storage-provisioner default-storageclass`; or set `storageClassName` |
| MySQL `CrashLoopBackOff` after password change | Data dir initialised with different credentials | `kubectl -n $NS logs mysql-0` | Restore original Secret, or `kubectl -n $NS delete pvc data-mysql-0` (dev only) |
| `FlywayValidateException` / `Migration checksum mismatch` | An applied migration file was edited | Logs at startup | Never edit applied migrations; add `V2__...`. Dev only: `flyway repair` or reset the DB |
| `Schema-validation: missing column` | Entity changed without migration | Logs at startup | Add a Flyway migration for the change |
| POST slow then `503 UPSTREAM_UNAVAILABLE` | SOAP host slow/unreachable or egress blocked | `logs \| jq 'select(.message\|test("soap.call"))'`; `exec <pod> -- sh -c 'timeout 5 bash -c "</dev/tcp/webservices.oorsprong.org/80" && echo open'` | Check egress/DNS/proxy; tune `SOAP_READ_TIMEOUT_MS`; wait for upstream |
| Immediate `503 UPSTREAM_CIRCUIT_OPEN` | Circuit opened after repeated failures | `curl :8081/actuator/circuitbreakers`; `logs \| grep circuit_transition` | Fix the upstream; the breaker half-opens after 30 s and closes after 3 successful calls. Reads keep working meanwhile |
| `502 UPSTREAM_INVALID_RESPONSE` | SOAP fault or contract change | `logs \| jq 'select(.message\|test("upstream.invalid_response"))'` | Compare upstream WSDL with the committed one; regenerate |
| `503` from ingress (nginx page, no JSON body) | No ready endpoints | `kubectl -n $NS get endpoints country-info-service` | Fix readiness (above) |
| `502/504` from ingress | Pod crashed mid-request or request exceeded `proxy-read-timeout` | ingress logs: `kubectl -n ingress-nginx logs deploy/ingress-nginx-controller \| tail` | Check app logs for that time; raise annotation if creates legitimately take longer |
| `404` from ingress for every path | Host header mismatch or ingress class missing | `kubectl -n $NS describe ingress`; `curl -H 'Host: country-info.local' http://$(minikube ip)/api/v1/countries` | Fix `/etc/hosts` / `INGRESS_HOST`; `minikube addons enable ingress` |
| HPA `<unknown>/70%` or not scaling | metrics-server missing or pods have no CPU request | `kubectl -n $NS describe hpa country-info-service`; `kubectl top pods -n $NS` | `minikube addons enable metrics-server`; keep `resources.requests.cpu` set |
| NetworkPolicy blocks traffic unexpectedly | Ingress controller namespace label differs | `kubectl get ns --show-labels \| grep ingress` | Adjust the `namespaceSelector` in `networkpolicy.yaml` |

## Tracing one request end to end

1. Every response carries `X-Correlation-Id`; every error body has the same value as `traceId`.
   Clients can also send their own (`X-Correlation-Id: abc-123`).
2. Find every log line for it across all replicas:

   ```bash
   kubectl -n $NS logs -l $APP --prefix --since=1h | grep '"correlationId":"abc-123"'
   # or structured:
   kubectl -n $NS logs -l $APP --since=1h | jq -c 'select(.correlationId=="abc-123") | {"@timestamp",level,message}'
   ```

   A failed create typically shows: `soap.call operation=CountryISOCode outcome=unavailable latencyMs=5003`,
   `resilience.retry attempt=1`, `attempt=2`, then `upstream.unavailable cause=SocketTimeoutException`.
3. Correlate with metrics for the same time window:

   ```bash
   kubectl -n $NS port-forward svc/country-info-service 8081:8081
   curl -s localhost:8081/actuator/prometheus | grep -E 'soap_client_requests_seconds_count|resilience4j_circuitbreaker_state|cache_gets_total'
   curl -s localhost:8081/actuator/circuitbreakers | jq
   curl -s 'localhost:8081/actuator/metrics/http.server.requests?tag=status:503' | jq
   ```

   Through port-forward to the Service you land on one pod; for all pods use Prometheus queries from
   `ARCHITECTURE.md` §6.
