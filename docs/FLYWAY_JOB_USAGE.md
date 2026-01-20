Flyway Job — How to use it and Notes

This document explains how to use the `k8s/flyway-job.yaml` Job to run Flyway database migrations in-cluster and describes operational notes and caveats.

Location
- Kubernetes manifest: `k8s/flyway-job.yaml`
- SQL migration source: ConfigMap named `flyway-sql` (or use a baked image / PVC)

Quick checklist before running
- [ ] Place your Flyway-compatible SQL migration files in a local `./sql` directory (e.g., `V1__init.sql`, `V2__add_indexes.sql`)
- [ ] Ensure `transaction-service-config` ConfigMap contains:
      - `SPRING_DATASOURCE_URL` (JDBC URL)
      - `SPRING_DATASOURCE_USERNAME`
- [ ] Ensure `transaction-service-secret` Secret contains:
      - `SPRING_DATASOURCE_PASSWORD`
- [ ] Ensure your cluster nodes can reach the database endpoint (RDS) and network rules/firewalls allow port 3306.

How to use it (PowerShell commands)

1) Create the ConfigMap from your local `./sql` directory (this maps each file into the ConfigMap):

```powershell
kubectl create configmap flyway-sql --from-file=./sql
```

2) Confirm your ConfigMap and Secret have the required keys (example):

```powershell
kubectl get configmap transaction-service-config -o yaml
kubectl get secret transaction-service-secret -o yaml
```

3) (Optional) Patch `transaction-service-config` to set the correct JDBC URL (replace host):

```powershell
kubectl patch configmap transaction-service-config --type merge -p (
  '{"data":{"SPRING_DATASOURCE_URL":"jdbc:mysql://your-rds-endpoint.rds.aliyuncs.com:3306/Transactions?useSSL=true&serverTimezone=UTC"}}'
)
```

4) Apply the Flyway Job manifest:

```powershell
kubectl apply -f k8s/flyway-job.yaml
```

5) Watch the Job logs to monitor migration progress:

```powershell
kubectl logs -f job/flyway-migrate
```

6) Confirm the Job finished successfully:

```powershell
kubectl get job flyway-migrate
kubectl get pods -l job-name=flyway-migrate
kubectl describe job flyway-migrate
```

7) Cleanup (if you want to remove the job after success):

```powershell
kubectl delete job flyway-migrate
kubectl delete configmap flyway-sql
```

Notes and caveats

- ConfigMap size limitation:
  - Kubernetes `ConfigMap` has size limits (etcd). Large or many SQL files might exceed limits. If your SQL files are large or numerous, prefer one of these approaches:
    - Create a small Docker image that contains your SQL files (build and push to registry) and use that image in the Job or an initContainer.
    - Use a PVC (PersistentVolumeClaim) with a CI job that writes SQL files to the volume and mount it in the Job.
    - Use an object storage (OSS/S3) and a small init script to download files at runtime.

- Secrets and credentials security:
  - The job reads `SPRING_DATASOURCE_PASSWORD` from a Kubernetes Secret. Avoid inline passwords in manifests and prefer Alibaba Secrets Manager or sealed-secrets for production.
  - Passing DB password on the Flyway CLI is necessary here; if you need stronger secrecy, consider mounting a flyway.conf file created at runtime with restrictive permissions.

- TLS / certificate verification:
  - If your JDBC URL uses `useSSL=true` and `verifyServerCertificate=true`, ensure the Flyway container trusts the RDS CA certificate. Options:
    - Use an image that includes the CA cert in the JVM truststore.
    - Mount a Secret containing the CA cert and configure the JVM truststore in the container command.
    - Temporarily use `verifyServerCertificate=false` during initial rollout (not recommended long term).

- Job retries and backoff:
  - The Job manifest sets `backoffLimit: 3` (retries). Kubernetes will retry the pod up to this many times. If migration repeatedly fails, inspect logs and fix the underlying issue.

- Idempotency and safety:
  - Flyway migrations are designed to be idempotent for applied versions. Ensure you use proper versioned migration names (`V1__...`, `V2__...`) and avoid destructive changes that cannot be rolled back.

- Running migrations from CI vs in-cluster Job:
  - Recommended: run migrations from CI (controlled environment) before deploying application changes. The in-cluster Job is convenient but consider pipeline-run migrations for production.

- RBAC / permissions:
  - If your cluster restricts job creation, you may need to create a ServiceAccount and RoleBinding allowing the operator CI user or deployment agent to create Jobs. Example manifests can be provided if required.

- Flyway configuration options:
  - The Job passes `-locations=filesystem:/flyway/sql` so Flyway will pick up files from the mounted ConfigMap. You can add `-placeholders.*` or other Flyway options via the `args` command if needed.

Troubleshooting checklist
- Job stays in CrashLoopBackOff: run `kubectl logs` to see Flyway error; common issues: DB unreachable, invalid credentials, SQL error.
- No files under /flyway/sql: verify the `flyway-sql` ConfigMap contains keys `V1__...` and was created from the correct directory.
- Permission issues on cert or config: ensure mounted Secrets have correct permissions and the container command references them properly.
