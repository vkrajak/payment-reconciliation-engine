# Real-Time Payment Reconciliation & Exception Engine

## Problem Statement:

  Multi-source payment reconciliation (internal ledger vs. PSP/acquirer feed vs. bank statement) is still handled via slow, batch-oriented
  processes in most payment platforms, leading to delayed mismatch detection, poor cash visibility, and manual exception handling. This
  project builds a real-time, event-driven engine that closes that gap.

## System Architecture

  Three source systems (internal ledger service, PSP/acquirer feed, bank statement feed via S3) publish events into dedicated Kafka
  topics. A central reconciliation engine consumes all three streams with idempotent, exactly-once-style processing, applies matching rules
  with configurable tolerance windows, and splits output into two paths: a matched store (Postgres settlement log) and an exception
  queue (dead-letter topic with automated mismatch classification). Redis powers live status lookups for an operations dashboard showing
  real-time mismatch rate and settlement lag. The entire system is containerized with Docker, deployed on Kubernetes, built via Jenkins CI/
  CD, and observed with Prometheus + Grafana.

## Architectural Design

```
                        Ledger Service
                              |
                    Internal Ledger Events
                              |
                              v

                     +--------------------+
                     |   Apache Kafka     |
                     |--------------------|
                     | ledger-events      |
                     | psp-events         |
                     | bank-events        |
                     +--------------------+
                              ^
                              |
          +-------------------+-------------------+
          |                                       |
          |                                       |
+----------------------+                +----------------------+
| PSP / Acquirer Feed  |                | Bank Statement Feed  |
| Card Processor Events|                | S3 File Drop         |
+----------------------+                +----------------------+

                              |
                              v

                 +-----------------------------+
                 |    Reconciliation Engine    |
                 |-----------------------------|
                 | Idempotent Consumers        |
                 | Matching Rules              |
                 +-----------------------------+
                       |                 |
             matched   |                 | mismatch
                       |                 |
                       v                 v

          +-------------------+    +-------------------+
          |   Matched Store   |    | Exception Queue   |
          | PostgreSQL        |    | Dead Letter Queue |
          +-------------------+    +-------------------+
                    |                       |
                    v                       v

          +-------------------+    +-------------------+
          | Redis Cache       |    | Ops Dashboard     |
          | Live Status       |    | Metrics & Alerts  |
          +-------------------+    +-------------------+

                    \               /
                     \             /
                      \           /
                       v         v

               +-------------------------+
               | Kubernetes Deployment   |
               | Docker + Jenkins +      |
               | Prometheus + Grafana    |
               +-------------------------+
```

## Architecture Flow
1. **Ingestion** — Ledger service, PSP feed, and bank statement feed (S3 file drop) each publish to their own Kafka topic (ledger events , psp-events , bank-events ).
2. **Reconciliation engine** — Kafka consumers process events idempotent (deduplicated via amount/timestamp tolerance. eventId ), apply matching rules with
3. **Matched path** — Successfully matched transactions are persisted to a Postgres-backed settlement log.
4. **Exception path** — Partial matches or no-matches are routed to a dead-letter queue with automatic classification (missing transaction, amount mismatch, duplicate, delayed settlement).
5. **Caching & reporting** — Redis caches live reconciliation status; a reporting service exposes REST APIs powering an operations dashboard (mismatch rate, settlement lag). Infrastructure

## Tech Stack
- Java
- Spring Boot
- Spring Data JPA
- Apache Kafka
- PostgreSQL
- Redis
- Docker
- Kubernetes
- Jenkins CI/CD
- AWS (S3, Lambda, ECS)
- Prometheus
- Grafana
- JUnit5

##  Folder Structure
```
payment-reconciliation-engine/
├── ingestion-service/       # Reads bank statements from S3, publishes to Kafka
├── reconciliation-service/  # Core matching engine (consumers + rules)
│   ├── consumer/            # LedgerConsumer, PspConsumer, BankConsumer
│   ├── matcher/             # MatchingStrategy, ToleranceRules
│   └── exception/           # DLQ handler, mismatch classifier
├── reporting-service/       # REST APIs for dashboard, reads Redis+Postgres
├── common-lib/              # Shared DTOs, Kafka config, error models
├── infra/
│   ├── docker-compose.yml   # Local dev: Kafka, Postgres, Redis, Zookeeper
│   ├── k8s/                 # Deployment, Service, HPA manifests per service
|   └── Jenkinsfile          # Build → test → docker push → deploy
├── monitoring/
│   └── grafana-dashboards.json
└── README.md 
```