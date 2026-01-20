# TransactionServicePilot - Technical Design Diagram

## System Architecture Overview

```mermaid
graph TB
    subgraph ClientLayer ["Client Layer"]
        Client[Banking Applications/APIs]
    end
    
    subgraph AppLayer ["Application Layer"]
        Controller[TransactionController<br/>REST API Endpoints]
    end
    
    subgraph ServiceLayer ["Service Layer"]
        TxService[TransactionService<br/>Business Logic]
        BalMgr[BalanceManager<br/>Cache Operations]
        TxRepo[TransactionRepository<br/>JPA Repository]
        AccRepo[AccountRepository<br/>JPA Repository]
        Scheduler[SyncScheduler<br/>Background Jobs]
        CacheInit[CacheInitializer<br/>Startup Population]
    end
    
    subgraph DataLayer ["Data Layer"]
        Redis[(Redis Cache<br/>In-Memory Storage)]
        MySQL[(MySQL Database<br/>Persistent Storage)]
        RedisScripts[reserve_balance.lua<br/>Atomic Operations]
    end
    
    subgraph Infrastructure ["Infrastructure"]
        K8s[Kubernetes<br/>Container Orchestration]
        Monitoring[Prometheus/Grafana<br/>Metrics & Monitoring]
        Actuator[Spring Actuator<br/>Health Checks]
    end
    
    Client --> Controller
    Controller -->|POST /v1/transactions| TxService
    Controller -->|GET /v1/accounts/balance| BalMgr
    
    TxService --> BalMgr
    TxService --> TxRepo
    TxService --> AccRepo
    
    BalMgr --> Redis
    
    Scheduler --> TxService
    Scheduler --> BalMgr
    Scheduler --> AccRepo
    
    CacheInit --> BalMgr
    CacheInit --> AccRepo
    
    TxRepo --> MySQL
    AccRepo --> MySQL
    
    Redis -->|Lua Scripts| RedisScripts
    
    TxService --> Monitoring
    Actuator --> K8s
    
    style Redis fill:#ff9999
    style MySQL fill:#99ccff
    style TxService fill:#99ff99
    style BalMgr fill:#ffcc99
```

## Data Flow Diagrams

### 1. Transaction Processing Flow

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant TxService
    participant BalanceManager
    participant Redis
    participant MySQL
    participant SyncScheduler
    
    Client->>Controller: POST /v1/transactions
    Controller->>TxService: process(TransactionRequest)
    
    Note over TxService: Idempotency Check
    TxService->>MySQL: Check existing transaction
    
    Note over TxService: Validation & Business Logic
    TxService->>BalanceManager: reserve(accountId, amount, txId)
    BalanceManager->>Redis: Execute Lua script (atomic)
    Redis-->>BalanceManager: ReserveResult.OK
    
    Note over TxService: Create PENDING transaction
    TxService->>MySQL: Save TransactionRecord (PENDING)
    
    Note over TxService: Update Account Balance
    TxService->>MySQL: Conditional DB update (optimistic locking)
    
    alt Success
        TxService->>MySQL: Update TransactionRecord (COMMITTED)
        TxService->>BalanceManager: commit(accountId, amount, txId)
        BalanceManager->>Redis: Update final balance, remove reservation
        TxService-->>Controller: TransactionResponse (COMMITTED)
    else Failure
        TxService->>BalanceManager: rollback(accountId, amount, txId)
        BalanceManager->>Redis: Restore available balance
        TxService->>MySQL: Update TransactionRecord (FAILED + retry info)
        TxService-->>Controller: TransactionResponse (FAILED)
    end
    
    Controller-->>Client: HTTP Response
    
    Note over SyncScheduler: Background Processing
    SyncScheduler->>TxService: reprocessPending() (scheduled)
    SyncScheduler->>BalanceManager: populateBalance() (cache sync)
```

### 2. Balance Query Flow

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant BalanceManager
    participant Redis
    participant MySQL
    
    Client->>Controller: GET /v1/accounts/{id}/balance
    Controller->>BalanceManager: getBalance(accountId)
    
    BalanceManager->>Redis: HGET balance:{accountId}
    
    alt Cache Hit
        Redis-->>BalanceManager: Balance data (sub-millisecond)
        BalanceManager-->>Controller: Cached balance
    else Cache Miss
        Controller->>MySQL: Fallback to database query
        MySQL-->>Controller: Account balance
    end
    
    Controller-->>Client: Balance response with currency
```

### 3. Retry and Recovery Flow

```mermaid
flowchart TD
    Start[Transaction Processing] --> Validate[Validate Request]
    Validate --> Reserve[Redis Reserve]
    
    Reserve -->|Success| DBUpdate[Database Update]
    Reserve -->|Failure| RetryCheck{Retry Count < 3?}
    
    DBUpdate -->|Success| Commit[Commit to Redis]
    DBUpdate -->|Failure| Rollback[Rollback Redis]
    
    Rollback --> RetryCheck
    RetryCheck -->|Yes| Backoff[Exponential Backoff<br/>5s * 2^retry-1]
    RetryCheck -->|No| PermanentFail[Permanent Failure<br/>Log & Metric]
    
    Backoff --> Schedule[Schedule Next Attempt]
    Schedule --> SyncScheduler[SyncScheduler Pickup]
    SyncScheduler --> Reserve
    
    Commit --> Success[Transaction COMMITTED]
    PermanentFail --> Failed[Transaction FAILED]
    
    style Success fill:#90EE90
    style Failed fill:#FFB6C1
    style Backoff fill:#FFE4B5
```

## Component Details

### Core Components

| Component | Responsibility | Technology |
|-----------|---------------|------------|
| **TransactionController** | REST API endpoints, request validation | Spring Boot REST |
| **TransactionService** | Business logic, transaction orchestration | Spring Service |
| **BalanceManager** | Redis cache operations, atomic reservations | Redis + Lua Scripts |
| **SyncScheduler** | Background sync, retry processing | Spring Scheduler |
| **AccountRepository** | Account persistence, optimistic locking | Spring Data JPA |
| **TransactionRepository** | Transaction persistence, audit trail | Spring Data JPA |

### Data Models

#### Account Entity
```
- id (Primary Key)
- accountNumber (Unique)
- balance (DECIMAL 19,4)
- availableBalance (DECIMAL 19,4)
- currency
- version (Optimistic Locking)
- timestamps
```

#### Transaction Entity
```
- id (Primary Key)
- txId (Business ID, Unique)
- sourceAccountId / destinationAccountId
- type (DEBIT/CREDIT/TRANSFER)
- amount (DECIMAL 19,4)
- status (PENDING/COMMITTED/FAILED)
- retryCount, nextAttemptAt
- timestamps, error details
```

#### Redis Data Structure
```
balance:{accountId} = {
  "balance": "cents_as_string",
  "available": "cents_as_string", 
  "currency": "USD"
}

reservation:{txId} = {
  "txId": "transaction_id",
  "amount_cents": "amount",
  "balanceKey": "balance:{accountId}"
} (TTL: 30 seconds)
```

## Key Design Patterns

### 1. **Optimistic Locking**
- JPA `@Version` annotation on Account entity
- Prevents lost updates in concurrent scenarios
- Automatic retry with exponential backoff

### 2. **Cache-Aside Pattern**
- Redis as primary read cache for balances
- Database as source of truth
- Graceful degradation when cache unavailable

### 3. **Saga Pattern (Simplified)**
- Two-phase approach: Reserve → Commit/Rollback
- Atomic Redis operations via Lua scripts
- Compensation actions for failure scenarios

### 4. **Circuit Breaker (Implicit)**
- Fallback to database when Redis fails
- Graceful degradation maintains availability

## Performance Characteristics

| Metric | Target | Implementation |
|--------|--------|----------------|
| **Balance Query Latency** | < 10ms | Redis cache (sub-millisecond) |
| **Transaction Throughput** | 10,000 TPS | Async processing + caching |
| **Availability** | 99.9% | Kubernetes + graceful degradation |
| **Consistency** | ACID | Database transactions + optimistic locking |

## Deployment Architecture

```mermaid
graph TB
    subgraph "Kubernetes Cluster"
        subgraph "Application Pods"
            App1[TransactionService Pod 1]
            App2[TransactionService Pod 2]
            App3[TransactionService Pod N]
        end
        
        subgraph "Data Services"
            RedisCluster[Redis Cluster<br/>3 Masters + Replicas]
            MySQL[MySQL RDS<br/>Master + Read Replicas]
        end
        
        subgraph "Infrastructure"
            LB[Load Balancer<br/>Service]
            HPA[Horizontal Pod Autoscaler]
            ConfigMap[ConfigMaps<br/>Environment Config]
            Secrets[Secrets<br/>DB Credentials]
        end
    end
    
    subgraph "Monitoring Stack"
        Prometheus[Prometheus<br/>Metrics Collection]
        Grafana[Grafana<br/>Dashboards]
        AlertManager[Alert Manager<br/>Notifications]
    end
    
    LB --> App1
    LB --> App2
    LB --> App3
    
    HPA --> App1
    HPA --> App2
    HPA --> App3
    
    App1 --> RedisCluster
    App2 --> RedisCluster
    App3 --> RedisCluster
    
    App1 --> MySQL
    App2 --> MySQL
    App3 --> MySQL
    
    App1 --> Prometheus
    App2 --> Prometheus
    App3 --> Prometheus
    
    Prometheus --> Grafana
    Prometheus --> AlertManager
```

## Error Handling Strategy

### Retry Policy
- **Transient Failures**: Retry up to 3 times with exponential backoff (5s, 10s, 20s)
- **Permanent Failures**: Log error, emit metrics, no further retries
- **Circuit Breaking**: Fallback to database when Redis unavailable

### Monitoring & Alerting
- Transaction throughput and latency metrics
- Error rate and retry count tracking
- Cache hit/miss ratios
- Database connection pool health
- Kubernetes pod health and resource usage

This design ensures high performance, reliability, and scalability while maintaining strict consistency requirements for financial transactions.