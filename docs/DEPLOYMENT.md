# Deployment Guide — Transaction Service Pilot

This document describes how to deploy `TransactionServicePilot` to Kubernetes and how to configure high availability and scaling.

## What I added
- `k8s/deployment.yaml` — example Deployment + Service + ConfigMap + Secret, with liveness/readiness probes and resource requests/limits.
- `k8s/hpa.yaml` — example HorizontalPodAutoscaler using CPU/memory utilization targets.
- `helm/` — simple Helm chart scaffold (Chart.yaml, values.yaml, templates/) for packaging and parameterized deployment.

## How this satisfies Requirement 4 (HA & Scalability)
- Deployment with `replicas: 2` and `restartPolicy: Always` ensures pod restarts and redundancy.
- `readinessProbe` and `livenessProbe` point to `/actuator/health/readiness` and `/actuator/health/liveness` endpoints so Kubernetes can restart or stop routing traffic to unhealthy pods.
- `k8s/hpa.yaml` defines an HPA that scales between 2 and 10 replicas based on CPU/memory — you can replace metrics with custom metrics (e.g., transactions/sec) if Prometheus Adapter is installed.
- Resource requests/limits are set to allow the HPA to make better scaling decisions.

## How this satisfies Requirement 10 (Configuration & Deployment)
- Application configuration is supplied via environment variables (ConfigMap) and secrets (Secret); the Helm chart `values.yaml` provides the same variables in a template-friendly way.
- Image repository and tag are parameterized so CI/CD can push new tags and upgrade the deployment.
- The example `Dockerfile` is already present and builds the fat jar used in the container image.
- Health checks and probes are documented and wired so Kubernetes can perform rolling updates with zero downtime.

## Deploying locally to a cluster
1. Build and push the image (replace with your registry):

```bash
# from repo root
./mvnw -DskipTests package
docker build -t ghcr.io/your-org/transaction-service-pilot:latest .
docker push ghcr.io/your-org/transaction-service-pilot:latest
```

2. Apply k8s manifests:

```bash
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/hpa.yaml
```

3. Verify pods & HPA:

```bash
kubectl get pods
kubectl get deploy
kubectl get hpa
kubectl describe hpa transaction-service-hpa
```

## Notes on rolling updates & zero-downtime
- Use `kubectl set image deployment/transaction-service-pilot transaction-service-pilot=ghcr.io/your-org/transaction-service-pilot:<new-tag>` to perform a rolling update.
- Health checks ensure readiness gating so pods don't receive traffic until they are ready.
- Keep `readinessProbe` strict and liveness probe less strict to avoid false restarts.

## Configuration via ConfigMaps/Secrets vs. Environment variables
- Keep secrets (DB passwords) in Kubernetes `Secret` and mount them as environment variables or files — avoid committing credentials in plain text.
- Use `ConfigMap` for non-sensitive configuration (batch sizes, backoff seconds, feature flags).
- Use `application-{profile}.properties` and `SPRING_PROFILES_ACTIVE` to switch between `local`, `test`, and `prod` profiles.

## How to gracefully degrade to DB-only when Redis fails
- The application already includes a DB fallback path (`processWithDbFallback` and `processTransferWithDb`) — in k8s, you can detect Redis availability via a readiness/liveness sidecar or a probe, and configure traffic routing if an entire zone is degraded.

## Next steps / improvements (optional)
- Add Prometheus metrics exposure and a Prometheus Adapter for scaling on custom metrics (transactions/sec).
- Add a Kubernetes `PodDisruptionBudget` to avoid scaling below minimum availability during maintenance.
- Add a `ServiceMonitor` CRD manifest (Prometheus Operator) to scrape metrics.
- Add Helm Chart `templates/hpa.yaml`, `templates/service.yaml`, and a test harness in `values.yaml` for CI.

---

If you want, I can also:
- Replace the reflective MeterRegistry test fix with explicit bean wiring.
- Add a `poddisruptionbudget.yaml` and a `servicemonitor.yaml` for Prometheus operator.
- Generate concrete Helm templates for the HPA and service.

Tell me which of those you'd like next and I will implement them.
