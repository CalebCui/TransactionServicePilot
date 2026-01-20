# TransactionServicePilot

Overview

This project implements a Real-Time Balance Calculation System (TransactionServicePilot) using Spring Boot. It processes financial transactions, keeps account balances in Redis for real-time reads and persists transactions and balances in MySQL.

Quick status

- Framework: Spring Boot (Java 17)
- Database: MySQL (local or remote)
- Cache: Redis
- Tests: Unit, Integration, Performance (JMeter)
- Reports directory: `test/report/`

Prerequisites

- Java 17
- Maven (wrapper `mvnw.cmd` is provided)
- MySQL (user provided credentials)
- Redis
- (Optional) JMeter for performance tests

Configuration

The project uses Spring profiles. Recommended profile for local testing: `local`.

Important configuration files:
- `src/main/resources/application.properties` (default)
- `src/main/resources/application-local.properties` (local overrides)

To use your local MySQL (example values you've provided):

- Database name: `Transactions`
- User: `root`
- Password: (your password)

You can set these via environment variables or directly update `application-local.properties`:

PowerShell example to run with your DB settings:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/Transactions?useSSL=false&serverTimezone=UTC"
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:SPRING_DATASOURCE_PASSWORD = "<your-password>"
.\mvnw.cmd test
```

Or run the application directly:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local";
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/Transactions?useSSL=false&serverTimezone=UTC";
$env:SPRING_DATASOURCE_USERNAME = "root";
$env:SPRING_DATASOURCE_PASSWORD = "<your-password>";
.\mvnw.cmd spring-boot:run
```

Database setup

The project expects the database and tables to exist. If not created, run the SQL DDL included in `docs/` or use the DDL below (adapt to your environment):

```sql
CREATE DATABASE IF NOT EXISTS `Transactions` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `Transactions`;

CREATE TABLE IF NOT EXISTS `account` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `account_number` VARCHAR(64) NOT NULL UNIQUE,
  `balance` DECIMAL(19,4) NOT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `transaction` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `transaction_id` VARCHAR(128) NOT NULL UNIQUE,
  `source_account` VARCHAR(64) NOT NULL,
  `destination_account` VARCHAR(64) NOT NULL,
  `amount` DECIMAL(19,4) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

Running tests and reports

- Unit tests: `.\mvnw.cmd test`
- Integration tests: `.\mvnw.cmd -Dspring.profiles.active=local verify` (or the appropriate profile)
- Performance tests (JMeter): the repo contains JMeter plans in `src/test/jmeter/` or `target/jmeter/`. If JMeter is not installed locally, keep the `.jmx` files and run them with JMeter GUI or CLI:

Example JMeter CLI command (run locally if JMeter installed):

```powershell
jmeter -n -t path\to\plan.jmx -l test\report\jmeter-results.jtl -e -o test\report\jmeter-report
```

Reports location

- Unit and integration test reports: `test/report/` (the build may create surefire and coverage reports under `target/`)
- Performance test reports: `test/report/` (JMeter output `.jtl` and generated HTML)

Deployment

- Dockerfile is included; to build a container:

```powershell
docker build -t transaction-service-pilot:latest .
```

- Kubernetes manifests are under `k8s/` and Helm chart under `helm/`. Deploy to cluster using:

```powershell
helm install transaction-service-pilot ./helm
```

Observability & Metrics

- The service exposes actuator endpoints (e.g., `/actuator/health`, `/actuator/metrics`) if actuator is enabled. Configure Prometheus scraping and setup Grafana for dashboards.

Where to find design and further docs

- `DESIGN.md` - high-level design and component interactions
- `docs/DEPLOYMENT.md` - deployment notes

Contact / Next steps

Tell me which item you'd like me to implement first (e.g., update `SyncScheduler`, add metrics, run tests and generate reports). I will proceed step-by-step and check with you after each change.
