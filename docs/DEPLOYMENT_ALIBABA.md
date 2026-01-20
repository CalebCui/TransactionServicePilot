DEPLOYING TransactionServicePilot to Alibaba Cloud (ApsaraDB for RDS)

This document explains how to deploy TransactionServicePilot to Kubernetes using Alibaba Cloud ApsaraDB for RDS (MySQL) as the managed database. It includes required configuration, secure secret handling, database migration steps, k8s manifest notes, connection-pool tuning, monitoring, and verification steps.

1) Overview
- App: TransactionServicePilot (Spring Boot)
- DB: Alibaba ApsaraDB for RDS (MySQL) — database name: `Transactions`
- Cache: Redis (use ApsaraDB Redis or self-managed Redis accessible from cluster)
- K8s manifests: `k8s/deployment.yaml` and `k8s/hpa.yaml` (already updated to include Prometheus annotations, Hikari envs, pod anti-affinity, and standardized Secret keys)

2) Prerequisites
- Kubernetes cluster (in same VPC or with network access to RDS)
- kubectl configured for cluster access
- Alibaba RDS instance (MySQL 8.x recommended)
- DNS/endpoint for RDS
- (Optional) Flyway CLI or Docker to run DB migrations
- Prometheus (optional) for metrics; Grafana for dashboards

3) Alibaba RDS setup checklist
- Create an RDS MySQL instance in the same VPC as the cluster (or ensure secure connectivity via VPC peering / VPN).
- Recommended engine: MySQL 8.x (matching JDBC driver in repo).
- Database name: `Transactions`.
- Create an application user (e.g. `app_user`) with least privileges needed (SELECT/INSERT/UPDATE/DELETE, CREATE/ALTER only for migrations if used).
- Configure security groups to allow only the cluster nodes (or a bastion) to connect on port 3306.
- Enable automated backups and set retention (e.g., 7 days).
- Enable Multi-AZ (High Availability) for production.
- Enable slow query log and performance monitoring.

4) JDBC configuration (Spring Boot)
- Use environment variables to supply JDBC config to the app. Example JDBC URL template (use SSL in production):

  SPRING_DATASOURCE_URL=jdbc:mysql://<RDS_HOST>:3306/Transactions?useSSL=true&serverTimezone=UTC
  SPRING_DATASOURCE_USERNAME=app_user
  SPRING_DATASOURCE_PASSWORD=<secret>

- We placed a template URL in `k8s/deployment.yaml` ConfigMap at `SPRING_DATASOURCE_URL`. Replace `<RDS_HOST>` with your RDS endpoint or patch the ConfigMap.

5) Creating Kubernetes Secret (PowerShell example)
- Store credentials in a Kubernetes Secret (recommended) or use Alibaba Cloud Secrets Manager and sync to k8s.

```powershell
kubectl create secret generic transaction-service-secret `
  --from-literal=SPRING_DATASOURCE_PASSWORD='REPLACE_WITH_DB_PASSWORD' `
  --from-literal=SPRING_REDIS_PASSWORD='' `
  --dry-run=client -o yaml | kubectl apply -f -
```

- (Optional) Put the username in the Secret instead of ConfigMap by adding `--from-literal=SPRING_DATASOURCE_USERNAME='app_user'`.

6) Patching the ConfigMap with RDS endpoint
- Either edit `k8s/deployment.yaml` locally and replace `<RDS_HOST>` or patch the ConfigMap directly:

```powershell
kubectl patch configmap transaction-service-config --type merge -p (
  '{"data":{"SPRING_DATASOURCE_URL":"jdbc:mysql://your-rds-endpoint.rds.aliyuncs.com:3306/Transactions?useSSL=true&serverTimezone=UTC"}}'
)
```

7) Database migrations (recommended)
- Recommended: run migrations from CI or as a separate k8s Job before rolling the app. Avoid running migrations inside the app process in production.
- If you want to run Flyway manually (Docker example): place migration SQL files in `./sql` locally and run:

```powershell
docker run --rm -v ${PWD}/sql:/flyway/sql flyway/flyway:9 `
  -url="jdbc:mysql://your-rds-endpoint.rds.aliyuncs.com:3306/Transactions?useSSL=true&serverTimezone=UTC" `
  -user="app_user" -password="YOUR_DB_PASSWORD" migrate
```

- Alternatively, enable Flyway integration in Spring Boot or run the commented `initContainer` in `k8s/deployment.yaml` (see file). I recommend CI-run Flyway or a Kubernetes Job for improved control.

8) What I changed in `k8s/` manifests for Alibaba deployment
- `k8s/deployment.yaml`
  - Added Prometheus scrape annotations to pod template:
    - `prometheus.io/scrape: "true"`, `prometheus.io/port: "8080"`, `prometheus.io/path: "/actuator/prometheus"`
  - Added soft PodAntiAffinity to spread pods across nodes for HA.
  - Moved DB password into `Secret` (`SPRING_DATASOURCE_PASSWORD`). ConfigMap contains the URL template and non-sensitive tuning parameters.
  - Added Hikari tuning envs via ConfigMap keys:
    - `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`
    - `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`
    - `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT_MS`
    - `SPRING_DATASOURCE_HIKARI_MAX_LIFETIME_MS`
  - Added a commented `initContainer` example for Flyway (disabled by default).

- `k8s/hpa.yaml`
  - Kept CPU/Memory autoscaling (v2) and added a note to use KEDA or Prometheus Adapter for transaction-throughput based scaling.

9) Connection-pool sizing guidance
- Formula: pool_per_pod ≈ floor((DB_max_connections - reserved) / desired_replicas) - safety_buffer
  - reserved: connections for admin/read-replicas/other services (e.g., 20)
  - safety_buffer: e.g., 5
- Example: DB_max_connections=200, reserved=20, replicas=3 => available=180; 180/3=60 => recommended pool size ≈ 55 (apply buffer); we set default `30` in ConfigMap as conservative.
- Update the ConfigMap values if you know the RDS instance's `max_connections` and expected replicas.

10) SSL / cert handling
- If you set `verifyServerCertificate=true`, you must ensure the JVM trusts the RDS CA certificate (either use the system truststore or mount the CA cert and configure `javax.net.ssl.trustStore`).
- If you cannot verify server certificate in initial setup, set `useSSL=true` and `verifyServerCertificate=false` temporarily (not recommended long-term).

11) Monitoring & observability
- Prometheus scrape annotations are present. Ensure Prometheus is configured to discover pods by annotations or use `ServiceMonitor` with Prometheus Operator.
- Expose `/actuator/prometheus` and `management.endpoints.web.exposure.include=prometheus,health,metrics,info` in prod profile.
- Add alerting for DB connections, slow queries, high CPU, memory pressure.

12) High availability & backups
- Use Multi-AZ RDS instance.
- Configure automated backups and daily snapshots; test restore flow.
- Consider read replicas for read-heavy workloads.

13) Deployment order and rollout strategy
- Run migrations first (CI or k8s job).
- Deploy the app (kubectl apply -f k8s/deployment.yaml).
- Use a rolling update strategy and monitor readiness/liveness.

14) Verification & sanity checks
- Validate manifests (client dry-run):

```powershell
kubectl apply --dry-run=client -f k8s/
```

- Deploy secrets and configmap, then test DB connectivity from the cluster:

```powershell
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/hpa.yaml
kubectl get pods -l app=transaction-service-pilot
kubectl run -it --rm db-test --image=mysql:8 -- bash
# inside pod:
mysql -h your-rds-endpoint.rds.aliyuncs.com -u app_user -p -D Transactions
```

15) CI/CD recommendations
- Store DB credentials in Alibaba Secrets Manager and fetch them in pipeline; avoid committing secrets.
- Add a pipeline step to run Flyway migrations.
- Add a pipeline step to run smoke tests against staging with a real DB.

16) Disaster recovery & rollback
- Test restore from snapshots regularly.
- Use read replicas to minimize RTO for read traffic.
