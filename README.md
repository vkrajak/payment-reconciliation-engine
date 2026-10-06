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
check-and-insert in the same transaction as the business write) — or Phase
5: exception classification + DLQ wiring, whichever you want to tackle
first. Say the word.
