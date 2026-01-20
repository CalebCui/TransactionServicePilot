TransactionServicePilot - Design Document

1. Overview

The TransactionServicePilot implements a Real-Time Balance Calculation System. Key goals: low-latency balance reads, correct concurrent transaction processing, durability of transactions, and scalable deployment.

2. Architecture

Components:
- Transaction Processor (Spring Boot REST API)
- Balance Manager (reads/writes balances; uses Redis as cache)
- Cache Layer (Redis) for quick balance reads
- Persistence Layer (MySQL) for durable transactions and account state
- SyncScheduler (background job to persist cached deltas to DB)
- Observability: Spring Actuator + Prometheus metrics

3. Data Model (summary)
- Account: id, account_number, balance (DECIMAL), version (for optimistic locking), timestamps
- Transaction: id, transaction_id (business id), source_account, destination_account, amount, status, created_at

4. Transaction processing flow
- Receive transaction request (validate fields).
- Generate unique transaction_id if not provided.
- Perform business validations (sufficient funds, no negative unless overdraft allowed).
- Use optimistic locking when updating account balances to prevent lost updates. Steps:
  - Read account from database (or cache), obtain current version.
  - Apply delta and attempt update using version check (WHERE id = ? AND version = ?).
  - If update fails due to version mismatch, retry (up to configured retries) or return a concurrency error.

- For performance, updates are first applied to Redis (atomic Lua script or Redis transaction) to allow sub-millisecond reads. The SyncScheduler persists deltas to MySQL.

5. SyncScheduler behavior
- Periodically (configurable interval, default <= 30s) reads pending changes from Redis and writes to MySQL.
- Retry policy: on transient failures, retry up to 3 times with exponential/backoff (base backoff=5s as requested). After 3 failed retries, log a permanent failure event (no requeue) and continue processing other items.
- On permanent failure, emit a logger event (and optionally alert via monitoring integration).

6. Concurrency & Consistency
- Use optimistic locking for DB writes to meet Requirement 5.
- Serialization for same-account updates is handled via optimistic retries and Redis atomic operations where appropriate.

7. Error handling
- Retry transient failures up to 3 times with exponential backoff (configurable).
- Permanent failures after retries are logged; SyncScheduler will not requeue.

8. High Availability & Deployment
- Containerized (Dockerfile included).
- Kubernetes manifests and Helm chart included. Use HPA to autoscale based on CPU or custom metrics (transaction throughput).
- Use readiness and liveness probes in Kubernetes manifests for pod lifecycle management.
- For Redis HA, recommend Redis Cluster or sentinel and support graceful fallback to DB-only operations when Redis unavailable.

9. Observability
- Expose `/actuator/health` and `/actuator/metrics`.
- Add metrics: transaction_throughput, transaction_latency_histogram, sync_scheduler_failures_total, sync_scheduler_retries_total.
- Log correlation IDs per transaction for end-to-end tracing.

10. Testing Strategy
- Unit tests: cover all business logic, aim >= 90% coverage.
- Integration tests: exercise DB and Redis interactions; use a test profile `local` that points to local MySQL and Redis instances.
- Performance tests: JMeter plans included. Target throughput tests: 100, 300, 500, 1000, 10000 tps; measure latency percentiles (30,50,70,90,95,99).

11. Files and locations
- Source: `src/main/java`
- Tests: `src/test/java`
- JMeter plans: `src/test/jmeter` or `target/jmeter`
- Reports: `test/report/`
- K8s manifests: `k8s/`
- Helm chart: `helm/`

12. Next steps to implement (per requirements gap analysis)
- Add missing metric counters and histograms; wire up Prometheus exposition.
- Implement SyncScheduler retry logic (3 retries, backoff) and permanent-failure logging.
- Ensure optimistic locking is implemented for account updates.
- Add readiness/liveness endpoints and ensure K8s manifests include probes and HPA configured.
- Provide JMeter command examples and ensure JMeter plans exist for required TPS scenarios.

13. Contact

For changes, I will update the repo, run tests, and export reports to `test/report/`. Please confirm and I will implement the first item from the list above (I recommend adding SyncScheduler retry and permanent-failure logging first).
