# ADR 008: Redis is a cache only; Postgres first, Redis second

## Context
The dashboard needs O(1) status lookups and cheap headline metrics, but a reconciliation system must never lose or
misreport money-related state because a cache failed or drifted.

## Decision
- Postgres is the only source of truth. Redis holds derived copies (`txn:status`, `metrics:*`) and a liveness signal
  (`engine:heartbeat:*`).
- Write-through order is Postgres commit, then Redis. In the engine the Redis write is registered `afterCommit`, so
  Redis can never show a match that rolled back.
- Redis failures are logged and counted, never thrown, in all three services. Readers treat any Redis error as a miss and
  fall back to Postgres (read-through repopulates the key).
- Client timeouts are 500ms, so an unreachable Redis degrades latency but cannot hang requests.
- TTLs bound staleness: 24h for transaction status, 5 min for metrics, 30s for heartbeats (absence IS the signal).
- A single `RedisKeys` class and `TxnStatusCodec` in `common-events` define key names, TTLs and JSON shape for every service.

## Consequences
- "What if Redis goes down?" has a one-line answer: the dashboard gets slower, no data is lost.
- A cached status can be briefly stale (for example between the engine commit and the classifier's later update); it
  self-corrects on the next write or TTL expiry. Resolve explicitly refreshes the cached status.
- Three writers share one key. Last write wins, guarded where it matters: resolve and the orphan upgrade only modify an
  entry that is still an open `EXCEPTION`.
