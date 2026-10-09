# country-info-project
Spring Boot 3 / Java 21 microservice that resolves a country name via the public oorsprong.org SOAP API, stores it in MySQL and exposes CRUD REST endpoints. Built for a flaky upstream: retries, circuit breaker, bulkhead, caching, idempotent creates (409), structured logs and Prometheus metrics. Docker + Kubernetes ready.
