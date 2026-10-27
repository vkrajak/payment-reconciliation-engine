# Payment Reconciliation Engine

Real-time reconciliation across ledger, PSP/acquirer, and bank statement feeds.
See `docs/blueprint.md` (or the original design doc) for the full architecture.
This README only covers what's built so far: **Phase 0** (local infra) and
**Phase 1** (event schemas).

## Phase 0 — Local infrastructure

Brings up: Kafka (KRaft mode, no Zookeeper), Schema Registry, Postgres, Redis,
LocalStack (S3 sim), and Kafka UI.

### Run it

```bash
docker compose up -d
```

First boot takes ~30-60s while `kafka-init` waits for Kafka to report healthy
before creating topics. Watch it:

```bash
docker compose logs -f kafka-init
```

### Verify everything is up

| Check | Command / URL |
|---|---|
| Kafka topics created | `docker exec recon-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list` |
| Kafka UI (browse topics/messages visually) | http://localhost:8080 |
| Schema Registry alive | `curl http://localhost:8081/subjects` (empty `[]` is correct — no schemas registered yet, that's Phase 1) |
| Postgres tables created | `docker exec -it recon-postgres psql -U recon -d recon -c '\dt'` |
| Redis alive | `docker exec -it recon-redis redis-cli ping` (expect `PONG`) |
| LocalStack S3 alive | `curl http://localhost:4566/_localstack/health` |

Expected tables from `\dt`: `matched_transactions`, `matched_transactions_default`,
`matched_transactions_2026_09_15/16/17`, `processed_events`, `exceptions`.

### Tear down

```bash
docker compose down          # keeps volumes (data persists)
docker compose down -v       # wipes all data, clean slate
```

### Known local-dev trade-offs (worth remembering for later)

- Single-node Kafka, replication factor 1 everywhere — fine for dev, never for prod.
- `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` is intentional: topics are only ever
  created explicitly via `infra/kafka/create-topics.sh`, so partition counts
  and retention always match the blueprint's design table, never an accidental default.
- Fixed `CLUSTER_ID` in `docker-compose.yml` so `docker compose down` (without `-v`)
  and `up` again reuses the same Kafka cluster identity instead of erroring.

---

## Phase 1 — `common-events` module (Avro schemas)

`common-events/src/main/avro/` holds five schemas, one per event contract in
the system:

| Schema | Topic | Produced by |
|---|---|---|
| `LedgerEvent.avsc` | `ledger-events` | ledger-simulator (Phase 2) |
| `PspEvent.avsc` | `psp-events` | psp-simulator (Phase 2) |
| `BankEvent.avsc` | `bank-events` | bank-file-ingester (Phase 2) |
| `ReconciliationResult.avsc` | `recon-results` | reconciliation-engine (Phase 3) |
| `ExceptionEvent.avsc` | `exception-events` | reconciliation-engine (Phase 3) |

Every field has a `doc` string explaining its purpose and any gotchas — read
the `.avsc` files directly, they're meant to double as design documentation,
not just wire format.

### Build it

```bash
cd payment-reconciliation-engine
mvn -pl common-events -am clean install
```

This runs the `avro-maven-plugin` during `generate-sources`, producing Java
POJOs (`LedgerEvent.java`, `PspEvent.java`, etc.) under
`common-events/target/generated-sources/avro/`, then compiles and installs
`common-events-0.1.0-SNAPSHOT.jar` into your local `~/.m2` repo so every
future module (`ledger-simulator`, `reconciliation-engine`, ...) can depend
on it as `<groupId>com.recon</groupId><artifactId>common-events</artifactId>`.

### Register schemas with Schema Registry

Not required to build, but required before any producer/consumer actually
publishes — Schema Registry needs to know the schemas before it can enforce
`BACKWARD` compatibility on future changes. We'll wire this into each
producer's Spring config automatically in Phase 2 (the `kafka-avro-serializer`
auto-registers on first publish by default) — no manual step needed unless
you want to pre-register and lock compatibility mode before any code runs.

### Sanity-check the generated POJOs

```bash
find common-events/target/generated-sources -name "*.java"
```

Expect to see `LedgerEvent.java`, `PspEvent.java`, `BankEvent.java`,
`ReconciliationResult.java`, `ExceptionEvent.java`, plus the shared
`TxnType.java`, `MatchStatus.java`, and `ExceptionCode.java` enums.

---

---

## Phase 2 — Simulators (`simulation-common`, `ledger-simulator`, `psp-simulator`, `bank-file-ingester`)

Three independently-deployable Spring Boot apps that produce realistic,
**correlated** events across all three sources, so the reconciliation-engine
(Phase 3) has something meaningful to match against.

### The scenario-coordinator pattern

A naive design would run three fully independent simulators, each picking
random behavior — but three independent coin flips mostly produce noise, not
the specific exception mix blueprint §6 describes. Instead:

1. `ledger-simulator` is the only place that decides a transaction's fate. On
   each scheduler tick it builds a `ScenarioPlan` (in `simulation-common`) —
   the "true" amount, and exactly how PSP and bank should each behave for
   this `transactionRef` (`EMIT_MATCHING`, `EMIT_MISMATCHED_AMOUNT`, `SKIP`,
   `EMIT_DUPLICATE`, or `EMIT_DELAYED`).
2. It publishes that plan as plain JSON to the dev-only `sim-scenarios`
   topic, then publishes its own `LedgerEvent` (Avro) to `ledger-events`.
3. `psp-simulator` and `bank-file-ingester` each consume `sim-scenarios`
   independently and realize their assigned behavior — with zero direct
   coupling to each other or to `ledger-simulator`'s internals.

This means the demo's exception-code distribution is tunable in one place
(`ledger-simulator`'s `application.yml` weights) without touching any other
module.

### Why `bank-file-ingester` is two things in one deployable

Unlike the other two, this module genuinely round-trips through a file,
matching the diagram's "S3 file drop" architecture:

- **`StatementFileWriter`** (simulation-only): buffers pending lines from
  `sim-scenarios` and periodically batches them into a CSV file written to
  an S3 bucket (LocalStack locally). Nothing like this exists once a real
  bank feed replaces the simulator.
- **`StatementFileWatcher`** (the production component): polls the bucket,
  parses new CSV files, and publishes `BankEvent` to `bank-events`. This
  class's logic is exactly what runs unchanged against a real bank's S3
  drop — only `S3Config`'s endpoint/credentials change.

Every `BankEvent.eventId` is derived deterministically
(`UUID.nameUUIDFromBytes(statementLineId)`) so re-processing the same file
(e.g. after a pod restart, since "already processed" tracking is in-memory
for now — a documented, deliberate Phase 2 limitation) republishes identical
`eventId`s, which the reconciliation-engine's idempotency layer (blueprint
§4.3) will safely no-op on rather than double-count.

### An important correction from Phase 1

The five schemas in `common-events` originally used snake_case field names
(`transaction_ref`, `event_id`, ...), matching the illustrative JSON in the
design doc. **This was corrected in Phase 2**: Avro's Java code generator
does not camelCase snake_case field names (`transaction_ref` generates
`setTransaction_ref`, not `setTransactionRef`), so all five schemas now use
camelCase field names (`transactionRef`, `eventId`, `pspFee`,
`statementLineId`, ...) to keep the generated POJOs idiomatic. This is a
wire-format-only change — re-run `mvn -pl common-events -am clean install`
if you built Phase 1 before this correction.

### New Maven modules (add to your build)

| Module | Type | Depends on |
|---|---|---|
| `simulation-common` | plain jar | — |
| `ledger-simulator` | Spring Boot app | `common-events`, `simulation-common` |
| `psp-simulator` | Spring Boot app | `common-events`, `simulation-common` |
| `bank-file-ingester` | Spring Boot app | `common-events`, `simulation-common` |

Root `pom.xml`'s `<modules>` list now includes all four.

### Run it

```bash
# infra must already be up (Phase 0)
docker compose up -d

# build everything, in dependency order (Maven reactor handles this automatically)
mvn clean install

# run each simulator in its own terminal
mvn -pl ledger-simulator spring-boot:run
mvn -pl psp-simulator spring-boot:run
mvn -pl bank-file-ingester spring-boot:run
```

### Verify it's working

| Check | How |
|---|---|
| Scenario plans flowing | Kafka UI (http://localhost:8080) → `sim-scenarios` topic → should show JSON messages appearing continuously |
| Ledger events flowing | Kafka UI → `ledger-events` topic → Avro messages (Kafka UI decodes via Schema Registry automatically) |
| PSP events flowing | Kafka UI → `psp-events` topic |
| Bank statement files landing in S3 | `aws --endpoint-url=http://localhost:4566 s3 ls s3://bank-statements/statements/` (or just check `bank-file-ingester` logs for `"Wrote statement file..."`) |
| Bank events flowing | Kafka UI → `bank-events` topic, and `bank-file-ingester` logs for `"Processed s3://..."` |
| Same `transactionRef` across all three topics | Search Kafka UI for a specific `transactionRef` value — should appear in `ledger-events`, and (usually) `psp-events` and `bank-events` too, matching amounts most of the time |

### Known Phase 2 limitations (intentional, documented, not deferred silently)

- `StatementFileWatcher`'s "already processed" tracking is in-memory only —
  a restart re-scans the whole bucket. Safe (idempotent event IDs), not yet
  efficient. Hardening slated for later phases.
- `PendingLineBuffer` (bank-file-ingester) is not persisted — buffered-but-unflushed
  lines are lost on restart. Fine for a dev simulator.
- No dead-letter handling yet in the simulators themselves — that pattern is
  introduced properly in Phase 3's reconciliation-engine.

## Phase 3 — `reconciliation-engine` (happy-path matching only)

The core of the system: a Kafka Streams topology that performs the 3-way
join across `ledger-events` / `psp-events` / `bank-events`, runs the
matching rule chain, and persists MATCHED outcomes to Postgres.

**Scope note:** this phase deliberately excludes three things that look
related but are separate roadmap items — the `processed_events`
idempotency-key check (Phase 4), exception classification + DLQ wiring for
transactions that *don't* match (Phase 5), and Redis caching (Phase 6). A
transaction that fails to match in this phase is logged and dropped, not
routed anywhere yet.

### How the topology works
A `SessionWindows` aggregation (not a chained two-way join) was used
specifically because it naturally models the blueprint's
`PENDING → PARTIALLY_MATCHED → MATCHED` state machine per `transactionRef` —
every new event just updates the aggregate in place, and the rule chain only
ever fires once the aggregate is complete.

### A design clarification worth knowing

Blueprint §5.2 describes three "rules", but rule 3 (settlement-lag /
timing-only mismatch) isn't a third competing strategy the way rules 1 and 2
are — it's a **modifier** applied on top of whichever of rules 1/2 already
matched, downgrading the status to `SETTLEMENT_LAG_WARNING`. It's
implemented as a post-processing step in `MatchRuleChain`, not as a third
`MatchRule` bean, with the reasoning written directly in that class's doc
comment.

### New Maven module

| Module | Type | Depends on |
|---|---|---|
| `reconciliation-engine` | Spring Boot app (Kafka Streams + JDBC) | `common-events` |

Add to root `pom.xml`'s `<modules>` list:
```xml
<module>reconciliation-engine</module>
```

### Run it

```bash
# infra (Phase 0) and at least one simulator (Phase 2) should already be running
mvn clean install
mvn -pl reconciliation-engine spring-boot:run
```

### Verify it's working

| Check | How |
|---|---|
| Topology started cleanly | Logs show `"Reconciliation topology built: ..."` on startup |
| Results flowing | Kafka UI (http://localhost:8080) → `recon-results` topic → Avro `ReconciliationResult` messages |
| Rows landing in Postgres | `docker exec -it recon-postgres psql -U recon -d recon -c "SELECT transaction_ref, match_status, currency, settlement_lag_ms FROM matched_transactions ORDER BY matched_at DESC LIMIT 10;"` |
| Non-matches visible (expected, not a bug) | Logs show `WARN ... failed to match ... would raise AMOUNT_MISMATCH or CURRENCY_MISMATCH in Phase 5, dropped for now` whenever a simulator deliberately produced a mismatch scenario |
| Rows landing in the right partition | Since "today" is outside the dated partitions seeded in Phase 0's `init.sql` (2026-09-15/16/17), rows land in `matched_transactions_default` — check there too, this is expected, not an error |

### Known Phase 3 limitations (intentional, not deferred silently)

- No idempotency-key check against `processed_events` — only a bare
  `ON CONFLICT (transaction_ref, match_version, business_date) DO NOTHING`
  as a crash-safety net. It silently no-ops a duplicate write but doesn't
  *detect or log* that one occurred, which blueprint §4.3's full design
  calls for. Phase 4 replaces this.
- Failed matches (currency mismatch, amount beyond tolerance) are logged
  and dropped — no `ExceptionEvent` is published yet. Phase 5.
- No Redis cache population yet. Phase 6.

## What's next

Phase 4: idempotency-key layer (`processed_events` table, proper
check-and-insert in the same transaction as the business write)

# exception-classifier

Phase 5 of the Real-Time Payment Reconciliation & Exception Engine.

Consumes **exception candidates** from `reconciliation-engine`, decides *why* each transaction failed to reconcile
(amount mismatch, missing source, etc.), and persists a fully auditable row to Postgres for ops triage.
It also owns the **dead-letter path**: technical failures are captured in a separate table and alerted on,
never mixed with business exceptions and never silently dropped.

## Where it sits

```
reconciliation-engine ──► exception-events (3 partitions, key = transaction_ref)
                                │
                                ▼
                       ┌──────────────────┐   ok     ┌────────────────────────┐
                       │ ExceptionCandidate│────────►│ exceptions (Postgres)   │──► dashboard-api (Phase 6)
                       │ Listener          │         └────────────────────────┘
                       │  validate         │
                       │  classify (rules) │   fails 3x (1s/2s/4s) or malformed
                       │  persist (1 txn)  │──────────────┐
                       └──────────────────┘              ▼
                                                  exception-events.DLT
                                                         │
                                                         ▼
                                              ┌──────────────────┐     ┌──────────────────────┐
                                              │ DeadLetterListener│───►│ dead_letter_events   │──► alert
                                              │ (raw bytes)       │     └──────────────────────┘
                                              └──────────────────┘

OrphanSweepJob (scheduled): MISSING_SOURCE with no LEDGER, older than grace ──► ORPHAN_EVENT
```

**Exception queue vs. DLQ:** business exceptions are expected operational noise and go to ops via the dashboard.
DLQ records are technical failures (poison pills, DB outages) and go to engineering. See
[ADR 007](../docs/adr/007-exception-queue-vs-dlq.md).

## Event contract

Topic `exception-events`, key = `transactionRef`, JSON value (`com.recon.events.exception.ExceptionCandidate`):

```json
{
  "candidateId": "c-001",
  "transactionRef": "TXN-1001",
  "trigger": "WINDOW_EXPIRED",
  "firstEventAt": "2026-10-06T10:00:00Z",
  "detectedAt":   "2026-10-06T10:30:00Z",
  "events": [
    { "source": "LEDGER", "eventId": "e1", "amount": 100.00, "currency": "INR",
      "occurredAt": "2026-10-06T10:00:00Z", "raw": {} }
  ],
  "priorResolution": null
}
```

| Field | Notes |
|---|---|
| `candidateId` | **Must be deterministic** for the same underlying state (the engine's `ExceptionCandidateFactory` does this). It is the idempotency key. |
| `trigger` | `WINDOW_EXPIRED`, `SOURCES_DISAGREE`, `DUPLICATE_REF`, `POST_RESOLUTION_EVENT`. A hint only: the classifier re-derives the code from `events`. |
| `events` | Snapshots of every source event relevant to the candidate. For `POST_RESOLUTION_EVENT`, only the late event(s). |
| `priorResolution` | `MATCHED` or `EXCEPTION`; only for late arrivals. |

## Classification rules

Ordered, first match wins (`@Order`). Each rule is a `ClassificationRule` bean, unit-tested in isolation.

| Order | Rule | Code | Fires when |
|---|---|---|---|
| 10 | `LateArrivalRule` | `LATE_ARRIVAL` | trigger is `POST_RESOLUTION_EVENT` |
| 20 | `DuplicateTransactionRefRule` | `DUPLICATE_TRANSACTION_REF` | same source has 2+ distinct `eventId`s, or trigger is `DUPLICATE_REF` |
| 30 | `CurrencyMismatchRule` | `CURRENCY_MISMATCH` | more than one distinct currency across sources (checked before amounts) |
| 40 | `AmountMismatchRule` | `AMOUNT_MISMATCH` | 2+ sources and max-min amount delta is greater than `tolerance` |
| 50 | `OrphanEventRule` | `ORPHAN_EVENT` | window expired, no `LEDGER`, and age is at least `window + orphan-grace` |
| 60 | `MissingSourceRule` | `MISSING_SOURCE` | fewer than 3 sources present |
| fallback | | `UNCLASSIFIED` | nothing matched (alerts engineering; never dropped) |

Behaviours worth knowing:

- **Orphans are two-step.** At window expiry a candidate with no ledger is first `MISSING_SOURCE`.
  `OrphanSweepJob` upgrades it to `ORPHAN_EVENT` once `orphan-grace` has passed since detection.
- **Late arrivals** are stored as their own `LATE_ARRIVAL` row *and* folded (events + sources) into the most recent
  non-resolved exception for that ref. The append only happens on first insert, so redelivery cannot double-append.
- **Tolerance:** a delta within tolerance is not an exception. If the engine emitted such a candidate, it falls to
  `UNCLASSIFIED`, which means the engine and classifier tolerances are out of sync.
- **Audit:** every row stores `rule_name` and `classification_detail` (what was compared), answering "why did the
  system say this?".

## Data model

Defined in `src/main/resources/db/migration/V5__exceptions_and_dead_letters.sql`.

- `exceptions`: one row per candidate. Unique on `candidate_id`. Status `OPEN` / `ACKNOWLEDGED` / `RESOLVED`.
  `sources_present` is a JSON array like `["LEDGER","PSP"]`; `raw_events` is a JSON array of event snapshots.
- `dead_letter_events`: unique on `(source_topic, source_partition, source_offset)`; keeps the raw payload (`BYTEA`),
  exception class, message and stacktrace. Status `OPEN` / `REPLAYED` / `DISCARDED`.

> If your Flyway migrations live in another module, move the file there and renumber if `V5` is taken.
> This module does not run Flyway itself.

## Delivery & idempotency guarantees

- At-least-once consumption, record-level offset commits (`ack-mode: record`).
- Classify + insert run in **one DB transaction**; the offset is committed after it succeeds.
- Redelivery is a no-op via `INSERT ... ON CONFLICT (candidate_id) DO NOTHING`.
- Result: effectively-once persistence; one candidate yields at most one `exceptions` row.

## DLQ behaviour

| Failure | Behaviour |
|---|---|
| Undeserialisable payload (poison pill) | Straight to `exception-events.DLT`, no retries |
| `MalformedCandidateException` (missing required fields) | Straight to DLT, no retries |
| Any other processing error (e.g. DB hiccup) | Retry 3x with exponential backoff (1s, 2s, 4s), then DLT |
| Persisting a DLT record fails | Consumer stalls and retries forever (it will not skip a DLT record) |

The DLT consumer never deserialises the payload, so a poison pill cannot fail twice. To capture other services'
DLTs, change the listener to a topic list or `topicPattern = ".*\\.DLT"`.

**Reprocessing a dead letter:** fix the cause, then republish the `payload` from `dead_letter_events` to
`exception-events` and set the row's `status` to `REPLAYED`. There is no automated replay tool in v1.

## Configuration

`src/main/resources/application.yml`. Environment variables override the defaults.

| Property / env var | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Broker address |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/recon` / `recon` / `recon` | Postgres |
| `recon.kafka.exception-topic` | `exception-events` | Input topic (auto-created, 3 partitions, 90d retention) |
| `recon.kafka.dlt-topic` | `exception-events.DLT` | Dead-letter topic (3 partitions, must be >= source partitions) |
| `recon.kafka.replicas` | `1` | Topic replication factor; use 3 outside local dev |
| `recon.classifier.tolerance` | `0.01` | **Must equal the engine's tolerance** |
| `recon.classifier.window` | `30m` | Engine session window (used by `OrphanEventRule`) |
| `recon.classifier.orphan-grace` | `30m` | Wait after detection before `MISSING_SOURCE` becomes `ORPHAN_EVENT` |
| `recon.classifier.sweep-interval-ms` | `60000` | How often `OrphanSweepJob` runs |
| `server.port` | `8085` | Actuator / health / metrics |

## Run locally

```bash
# infra (Kafka/Redpanda + Postgres) from your existing docker-compose, with V5 applied to the DB
mvn -pl exception-classifier -am spring-boot:run
```

Or in Docker (build context is the repo root):

```bash
docker build -f exception-classifier/Dockerfile -t exception-classifier .
```

A ready-to-merge compose service is in `docker-compose.exception-classifier.snippet.yml`.

### Smoke test

```bash
# 1. A candidate: should classify as MISSING_SOURCE (missing BANK)
echo '{"candidateId":"c-001","transactionRef":"TXN-1001","trigger":"WINDOW_EXPIRED","firstEventAt":"2026-10-06T10:00:00Z","detectedAt":"2026-10-06T10:30:00Z","events":[{"source":"LEDGER","eventId":"e1","amount":100.00,"currency":"INR","occurredAt":"2026-10-06T10:00:00Z"},{"source":"PSP","eventId":"e2","amount":100.00,"currency":"INR","occurredAt":"2026-10-06T10:00:02Z"}]}' \
  | rpk topic produce exception-events -k TXN-1001

psql -c "SELECT transaction_ref, exception_code, rule_name, classification_detail FROM exceptions;"

# 2. Idempotency: publish the same message again, count must stay 1
psql -c "SELECT count(*) FROM exceptions;"

# 3. Poison pill: after ~7s a row appears in dead_letter_events
echo 'not json' | rpk topic produce exception-events
psql -c "SELECT source_topic, source_offset, exception_class FROM dead_letter_events;"
```

## Tests

```bash
mvn -pl exception-classifier test      # ClassificationChainTest (pure unit tests, no infra)
mvn -pl exception-classifier verify    # also runs ExceptionServiceIdempotencyIT (Testcontainers Postgres; needs Docker)
```

- `ClassificationChainTest`: one test per rule, including currency-before-amount ordering, tolerance boundary, and
  the orphan threshold.
- `ExceptionServiceIdempotencyIT`: a redelivered candidate is stored once; a redelivered late arrival is appended once.

## Observability

Scrape `http://localhost:8085/actuator/prometheus`. Health probes at `/actuator/health/liveness` and `/readiness`.

| Metric | Meaning |
|---|---|
| `recon_exceptions_total{exception_code}` | New exceptions persisted, by code |
| `recon_dlq_total{service}` | Dead letters persisted (alert on any increase) |
| `recon_orphan_upgrades_total` | `MISSING_SOURCE` rows upgraded to `ORPHAN_EVENT` |

Alert rules are in `monitoring/alerts/recon-exceptions.yml`: DLQ activity, any `UNCLASSIFIED`, and exception rate
above 5% of volume.

## Scaling & operations

- Run **at most 3 replicas** (the topic has 3 partitions); extra pods sit idle.
- Safe to run several replicas: inserts are idempotent and the orphan sweep is an idempotent `UPDATE`.
- Redis is not used by this module; Phase 6 adds caching on the read side.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Rows with `UNCLASSIFIED` | Engine and classifier `tolerance` differ, or the engine emitted a candidate no rule covers |
| Everything goes to the DLT | Wrong deserializer config or producer sending a different shape; check `exception_message` in `dead_letter_events` |
| Nothing consumed | Check consumer group `exception-classifier` lag; confirm the topic exists and `KAFKA_BOOTSTRAP` is reachable |
| Many `ORPHAN_EVENT`s | Ledger feed is down or delayed beyond `window + orphan-grace` |
| DLT consumer stalled | Postgres unreachable; it retries indefinitely by design |

## Package layout

```
com.recon.classifier
├── config/        ClassifierProperties, KafkaConfig (topics, error handler, DLT recoverer, DLT consumer factory)
├── consumer/      ExceptionCandidateListener, DeadLetterListener
├── rules/         ClassificationRule, ClassificationChain, one class per rule
├── service/       ExceptionService (classify + persist, one txn), MalformedCandidateException
├── persistence/   ExceptionRepository, DeadLetterRepository (JdbcTemplate, ON CONFLICT)
└── job/           OrphanSweepJob
```

## Adding a new exception code

1. Add the value to `ExceptionCode` (in `common-events`).
2. Create a `ClassificationRule` bean with an `@Order` that places it correctly in the chain.
3. Add a case to `ClassificationChainTest` (and to the chain list in `ExceptionServiceIdempotencyIT` if relevant).

# Phase 6: what to add to the repo

Redis caching layer + dashboard-api REST endpoints. Unzip over the repo root; paths match the monorepo layout.

## 1. Files

**New**

| Path | What |
|---|---|
| `dashboard-api/` | New module: REST API, Redis cache, SSE stream, tests, Dockerfile, README |
| `common-events/.../events/cache/` | `RedisKeys`, `TxnStatusDoc`, `TxnStatusCodec` (shared key names, TTLs, JSON shape) |
| `reconciliation-engine/.../engine/cache/` | `TxnStatusWriter` (write-through), `EngineHeartbeat`, `EngineCacheConfig` |
| `exception-classifier/.../classifier/cache/TxnStatusWriter.java` | Updates `txn:status` for exceptions |
| `docs/adr/008-redis-cache-only-write-through.md` | Decision record |
| `monitoring/alerts/recon-dashboard.yml` | Prometheus alert rules |
| `docker-compose.dashboard-api.snippet.yml` | Compose service to merge |

**Replaced (Phase 5 files, overwrite them)**

| Path | Change |
|---|---|
| `exception-classifier/.../consumer/ExceptionCandidateListener.java` | After the DB commit, updates Redis (skips `LATE_ARRIVAL`) |
| `exception-classifier/.../persistence/ExceptionRepository.java` | `upgradeMissingToOrphan` now `RETURNING transaction_ref` and returns `List<String>` |
| `exception-classifier/.../job/OrphanSweepJob.java` | Refreshes Redis for upgraded refs |

The Phase 5 tests (`ClassificationChainTest`, `ExceptionServiceIdempotencyIT`) are unaffected.

# dashboard-api

Phase 6 of the Real-Time Payment Reconciliation & Exception Engine.

Read/triage API for the ops dashboard. Serves live metrics and transaction status from **Redis** (fast path), falls back
to **Postgres** (source of truth), lets ops resolve exceptions with an audit trail, and streams new exceptions/matches
over **Server-Sent Events**. This module never writes matches or classifies exceptions; it only reads them, plus the
resolve action.

```
                         ┌───────────────────────────── dashboard-api (8086) ─────────────────────────────┐
 browser / Phase 7 UI ──►│ /metrics/summary   Redis metrics:* ──miss──► Postgres aggregates ──► write back │
                         │ /transactions/{ref} Redis txn:status ─miss──► Postgres (match/exception) ──► WB │
                         │ /exceptions[/{id}] Postgres (JPA)         POST /resolve ──► Postgres, then Redis │
                         │ /engine/health     Redis engine:heartbeat:*                                      │
                         │ /stream/live  ◄── Kafka recon-results (matches, batched) + exceptions table poll │
                         └──────────────────────────────────────────────────────────────────────────────────┘
 written elsewhere:  engine ─► txn:status (matches), engine:heartbeat   |   exception-classifier ─► txn:status (exceptions)
```

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/metrics/summary?date=yyyy-MM-dd` | Defaults to today in `dashboard.timezone`. `servedFrom` is `CACHE` or `DATABASE`. |
| GET | `/api/v1/exceptions?status=OPEN&code=AMOUNT_MISMATCH&page=0&size=20` | Newest first. `size` capped at 100. Bad `status`/`code` returns 400. |
| GET | `/api/v1/exceptions/{id}` | Drill-down: raw events from every source plus a per-field `comparison` with a `differs` flag. |
| POST | `/api/v1/exceptions/{id}/resolve` | Body `{ "resolvedBy": "...", "note": "..." }`. 200, 404 unknown, 409 already resolved, 400 invalid. |
| GET | `/api/v1/transactions/{transactionRef}` | Redis first, Postgres fallback (written back to Redis). 404 if unknown. |
| GET | `/api/v1/stream/live` | SSE. Events: `connected`, `exception`, `matches`. |
| GET | `/api/v1/engine/health` | **Addition beyond the blueprint table.** `UP` / `DEGRADED` / `DOWN` / `UNKNOWN` from heartbeats. |
| GET | `/actuator/prometheus` | Micrometer metrics. Health probes at `/actuator/health/liveness` and `/readiness`. |

Errors use RFC 7807 problem responses (`{"status":404,"detail":"..."}`).

### Examples

```bash
curl 'localhost:8086/api/v1/metrics/summary?date=2026-10-06'
curl 'localhost:8086/api/v1/exceptions?status=OPEN&size=5'
curl 'localhost:8086/api/v1/exceptions/42'
curl -X POST localhost:8086/api/v1/exceptions/42/resolve \
     -H 'Content-Type: application/json' \
     -d '{"resolvedBy":"ops.user","note":"Confirmed with bank, ledger corrected"}'
curl 'localhost:8086/api/v1/transactions/TXN-1001'
curl localhost:8086/api/v1/engine/health
curl -N localhost:8086/api/v1/stream/live          # -N = don't buffer
```

Abridged `/transactions/{ref}` response:

```json
{ "transactionRef": "TXN-1001", "status": "MATCHED", "exceptionCode": null,
  "matchedAt": "2026-10-06T06:00:00Z", "updatedAt": "2026-10-06T06:00:00Z",
  "sources": ["BANK","LEDGER","PSP"], "servedFrom": "CACHE" }
```

`status` is one of `MATCHED`, `MATCHED_WITH_TOLERANCE`, `EXCEPTION`, `RESOLVED`.

## Redis keys

All names and TTLs live in one class, `common-events/.../cache/RedisKeys.java`, shared by engine, classifier and this API.

| Key | Value | TTL | Written by |
|---|---|---|---|
| `txn:status:{ref}` | `{status, matched_at, sources, exception_code, updated_at}` | 24h | engine (matches), classifier (exceptions), this API (resolve, read-through) |
| `metrics:summary:{date}` | full summary JSON (what `/metrics/summary` reads) | 5 min | this API |
| `metrics:mismatch_rate:{date}` | float, percent | 5 min | this API |
| `metrics:settlement_lag:p50\|p95\|p99:{date}` | float, milliseconds | 5 min | this API |
| `engine:heartbeat:{partition}` | epoch millis | 30s | engine, every 10s per owned partition |

`metrics:summary` is an addition so one request is one Redis read; the blueprint's per-metric keys are written too.

## Behaviour you should know

- **Redis is a cache, never the durability boundary.** Every Redis call goes through `CacheGateway`: failures are logged,
  counted (`dashboard_cache_requests_total{result="error"}`) and treated as a miss. Redis down = slower dashboard, no
  data lost. Client timeouts are 500ms so a dead Redis cannot hang requests.
- **Write-through order is Postgres first, Redis second.** Resolve commits to Postgres, then updates Redis only if it
  currently shows that transaction as an open `EXCEPTION` (it never overwrites a newer `MATCHED`).
- **Most recent wins in the Postgres fallback.** If a transaction has both a match and an exception, the later of the two
  decides the status. `LATE_ARRIVAL` rows are bookkeeping and ignored for status.
- **Metrics definitions.** `totalTransactions` = distinct matched + distinct exception transactions for the day.
  `mismatchRatePct` = exceptions / total. `LATE_ARRIVAL` is excluded. `openExceptions` is the all-time OPEN backlog,
  not limited to the day. Lag values are milliseconds from `matched_transactions.settlement_lag_ms`.
- **Day boundaries** use `dashboard.timezone` (default `Asia/Kolkata`) for exceptions (by `first_detected_at`) and the
  `business_date` column for matches.
- **Pre-warming.** `MetricsRefreshJob` recomputes today's summary every 5 minutes (same as the TTL).
- **Live stream.** New exceptions are pushed from the `exceptions` table (so they carry the *classified* code), polled
  every second. Matches are tailed from the `recon-results` topic and sent as one batched `matches` event every 500ms:
  `{count, dropped, sample[]}`, never one SSE message per match. The payload is passed through untouched, so it does not
  depend on the `recon-results` schema. Nothing is buffered while no browser is connected. Each instance uses a unique
  Kafka group id, so every instance sees every record.

## Configuration (`application.yml`)

| Property / env | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/recon` / `recon` / `recon` | Postgres |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Only for the live match feed |
| `dashboard.timezone` | `Asia/Kolkata` | Default "today" and exception day windows |
| `dashboard.cors.allowed-origins` | `http://localhost:3000,http://localhost:5173` | Phase 7 UI dev servers |
| `dashboard.engine.expected-partitions` | `6` | Partitions of ledger/psp/bank topics (for engine health) |
| `dashboard.live.matches-enabled` | `true` | Set `false` to disable the Kafka match feed (SSE still serves exceptions) |
| `dashboard.live.matches-topic` | `recon-results` | Topic to tail |
| `dashboard.metrics.refresh-ms` | `300000` | Pre-warm interval |

## Run

```bash
# Postgres (with the Phase 5 `exceptions` table and your `matched_transactions`), Redis, optionally Kafka
mvn -pl dashboard-api -am spring-boot:run
# or
docker build -f dashboard-api/Dockerfile -t dashboard-api . && docker run --network host dashboard-api
```

## Tests

```bash
mvn -pl dashboard-api test      # unit: diff assembler, metrics service (cache hit/miss/zero traffic), lookup service
mvn -pl dashboard-api verify    # + DashboardApiIT (Testcontainers Postgres and Redis; needs Docker)
```

`DashboardApiIT` covers: list filters/pagination/validation, side-by-side diff, resolve (audit fields, 409 on repeat,
400/404 cases, `X-Resolved-By` header), Postgres-then-Redis lookup with TTL, resolve refreshing the cached status,
metrics compute-then-cache with percentiles, and engine health UP/DEGRADED/DOWN. SSE and the Kafka feed are verified
manually (below).

### Manual SSE check

```bash
curl -N localhost:8086/api/v1/stream/live
# in another terminal: insert/classify an exception (see exception-classifier README smoke test)
# expected: "event: exception" with the classified code within ~1s; "event: matches" batches if recon-results is flowing
```

## Metrics

`dashboard_cache_requests_total{result="hit|miss|error"}` (cache effectiveness and Redis health), plus the standard
Spring Boot / JVM / HTTP server metrics. Alert rules: `monitoring/alerts/recon-dashboard.yml`.

## Known limitations (v1)

- **No authentication.** `resolvedBy` comes from the body or `X-Resolved-By` and is trusted. Put it behind your gateway
  or add Spring Security before exposing it. This is a deliberate v1 scope cut.
- **Live exceptions are best-effort.** The poller follows `id` order; a row from a slow transaction that commits with a
  lower id than one already pushed can be missed in the stream. `GET /exceptions` is authoritative.
- **No cache-stampede protection.** On TTL expiry several concurrent requests can each recompute; the pre-warm job makes
  this unlikely for "today". Add a lock if traffic grows.
- **One resolve per exception, no reopen/acknowledge endpoints** (not in the blueprint).
- **Redis heartbeats show liveness, not lag.** Consumer lag lives in Prometheus/Grafana (Phase 10).


## 2. Build config edits (not shipped as files, since I can't see your poms)

**Root `pom.xml`**: add `<module>dashboard-api</module>`.

**`common-events/pom.xml`**: add (version managed by the Spring Boot BOM):

```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

**`reconciliation-engine/pom.xml`** and **`exception-classifier/pom.xml`**: add if not already present:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**`application.yml` of the engine and the classifier**: add (the Phase 0 compose Redis service is assumed to be `redis`):

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 500ms
      connect-timeout: 500ms
```

Engine only (optional): `recon.heartbeat.interval-ms: 10000`.

**docker-compose**: merge `docker-compose.dashboard-api.snippet.yml`; add `REDIS_HOST: redis` to the classifier's environment.

## 3. Engine hook (the one manual integration point)

I haven't seen your engine code, so call the writer **right after the `matched_transactions` insert** succeeds:

```java
private final TxnStatusWriter txnStatusWriter;   // com.recon.engine.cache.TxnStatusWriter

// after the Postgres insert for a match:
txnStatusWriter.recordMatched(
        transactionRef,
        tolerance ? TxnStatusDoc.MATCHED_WITH_TOLERANCE : TxnStatusDoc.MATCHED,
        matchedAt,                                   // Instant
        List.of("LEDGER", "PSP", "BANK"));           // the sources that took part
```

- If the call runs inside a Spring `@Transactional` method, the Redis write is automatically deferred to `afterCommit`.
  Inside a plain Kafka Streams processor (no Spring transaction) it runs immediately, so call it only after the insert returned.
- `EngineHeartbeat` assumes Spring Kafka's `StreamsBuilderFactoryBean` (`@EnableKafkaStreams`). With a hand-built
  `KafkaStreams` it stays idle; tell me and I'll adapt it.
- Engine code needs `@EnableScheduling`; `EngineCacheConfig` provides it.

## 4. Assumptions to check

- `matched_transactions` has the blueprint columns used by the SQL: `transaction_ref, match_version, match_status,
  ledger_amount, psp_amount, bank_amount, settlement_lag_ms, matched_at, business_date`. If your Phase 3-4 DDL renamed
  any, adjust `MetricsRepository` and `TransactionLookupRepository` (the only two classes that touch that table).
- The `recon-results` topic exists if you want the live match feed (`dashboard.live.matches-enabled: false` otherwise).
- Spring Boot 3.3+ / Java 21 (`buildConsumerProperties(null)` and `ProblemDetail` need Boot 3.2+).

## 5. Verify end to end

```bash
docker compose up -d                       # postgres, redis, broker, classifier, dashboard-api
curl localhost:8086/actuator/health

# 1. An exception (see exception-classifier README smoke test), then:
redis-cli GET txn:status:TXN-1001          # EXCEPTION / MISSING_SOURCE, TTL ~86400
curl localhost:8086/api/v1/transactions/TXN-1001      # servedFrom CACHE
curl localhost:8086/api/v1/exceptions?status=OPEN
curl -X POST localhost:8086/api/v1/exceptions/1/resolve -H 'Content-Type: application/json' \
     -d '{"resolvedBy":"ops.user","note":"checked"}'
redis-cli GET txn:status:TXN-1001          # now RESOLVED

# 2. Metrics cached for 5 minutes
curl "localhost:8086/api/v1/metrics/summary"           # DATABASE, then CACHE on the 2nd call
redis-cli TTL metrics:mismatch_rate:$(date +%F)

# 3. Redis outage: the API keeps answering from Postgres
docker compose stop redis && curl localhost:8086/api/v1/transactions/TXN-1001   # servedFrom DATABASE
docker compose start redis

# 4. Engine heartbeat (with the engine running)
redis-cli KEYS 'engine:heartbeat:*'        # 6 keys; curl localhost:8086/api/v1/engine/health -> UP
```

# Phase 7, part 1: what to add to the repo

Unzip over the repo root. Everything is new and lives in `dashboard-ui/`; nothing existing is modified.

1. Install Node.js 20 or newer (the only new software): `node -v`.
2. `cd dashboard-ui && npm install`
3. Start the stack so `dashboard-api` is reachable on `localhost:8086` (Postgres, Redis, Kafka, engine, classifier, dashboard-api).
4. `npm run dev`, then open `http://localhost:3000`.

Check it works:

| Do this | You should see |
|---|---|
| Open Overview | A matched percentage that equals `curl localhost:8086/api/v1/metrics/summary` (`matchRatePct`) |
| Check the engine panel | Six partition cells, green when the engine is beating, dashed red when missing |
| Raise an exception through the pipeline | It appears in the Live feed within a few seconds, with a link to `/exceptions/{id}` (404 page until part 2) |
| Stop `dashboard-api` | A red banner "Cannot reach the dashboard API"; start it again and press **Try again** |
| Find a transaction with a known ref | Status, cause and sources; an unknown ref gives a plain "No transaction found" sentence |

# Phase 7, part 2: what to add to the repo

Completes `dashboard-ui` (Exceptions triage, drill-down with resolve, Docker/nginx). Unzip over the repo root,
**after** part 1. Nothing outside `dashboard-ui/` changes, and `package.json` / `package-lock.json` are unchanged,
so there is nothing new to install.

## 1. Files

**Replaced (part 1 files, overwrite them)**

| Path | Change |
|---|---|
| `src/pages/ExceptionsPage.tsx` | Part 1 stub becomes the triage list |
| `src/App.tsx` | Adds the `exceptions/:id` route |
| `src/components/Layout.tsx` | Adds the "Resolving as …" control to the header |
| `src/styles.css` | Same file plus the part 2 rules (list, drill-down, resolve form) |
| `src/test/fakes.ts` | `mockFetch` now exposes the request init (needed to inspect POST bodies) |
| `src/test/fixtures.ts` | Adds exception fixtures |
| `README.md` | Final version |

**New**

| Path | What |
|---|---|
| `src/pages/ExceptionDetailPage.tsx` | Side-by-side diff, classification explanation, resolve form, audit view |
| `src/components/OperatorControl.tsx`, `src/hooks/useOperator.ts` | Operator name, remembered in the browser |
| `src/lib/compare.ts` | Comparison columns, cell formatting, classification as readable rows |
| `src/**/*.test.*` | 5 new test files (list below) |
| `Dockerfile`, `nginx.conf.template`, `.dockerignore` | Multi-stage image, nginx with SSE-safe proxy |
| `docker-compose.dashboard-ui.snippet.yml` | Compose service to merge |

New tests: `ExceptionsPage`, `ExceptionDetailPage`, `useOperator`, `compare`, `client` (plus the replaced `fakes.ts`/`fixtures.ts`).

## 2. Verify

```bash
cd dashboard-ui
npm test            # 34 tests
npm run build
npm run dev         # http://localhost:3000 (dashboard-api on :8086)
```

| Do this | You should see |
|---|---|
| Click an Overview "Exceptions by cause" row | `/exceptions?code=…` with that cause pre-selected and Status = Open |
| Open an exception from the list | Sources side by side; differing rows highlighted; a source that never reported shows "No event" |
| Raise a new exception through the pipeline while the list is open | A banner "1 new exception arrived…" (rows do not move); **Show it** adds it |
| Resolve it with a name and a note | The form is replaced by Resolved + who/when/note; the list and Overview open-count update |
| `curl localhost:8086/api/v1/exceptions/<id>` | `status: RESOLVED`, your `resolvedBy` and note |
| Resolve the same exception from a second tab | "Someone resolved this exception while you were reading it", then their resolution |
| Stop `dashboard-api` | Red "Cannot reach the dashboard API" banner with **Try again**; recovers when it is back |

Docker (compose):

```bash
# merge docker-compose.dashboard-ui.snippet.yml under services:, then
docker compose up -d --build dashboard-ui
curl localhost:3000/healthz                      # ok
curl localhost:3000/api/v1/metrics/summary       # proxied to dashboard-api
```

Open `http://localhost:3000/exceptions/<id>` directly and refresh: it should load (deep-link fallback).
Leave the Overview open for a minute: the live panel should stay "Live" (no buffering stall).

## 3. Assumptions to check

- The dashboard-api compose service is named `dashboard-api` and listens on 8086. If not, set `DASHBOARD_API_URL` in the snippet.
- Docker Compose DNS (`127.0.0.11`) is used by nginx. For Kubernetes (Phase 9) set `NGINX_RESOLVER` to the cluster DNS.
- The image build has not been run here (no Docker in my environment). The nginx config itself was run against a fake API and behaved as intended: SPA fallback, caching headers, proxy, unbuffered live stream.

## 4. Not covered

Authentication and real operator identity, an Acknowledge/reopen action (the API has none), a trend view, and rendering the raw `recon-results` payload. See the README's known limits.

