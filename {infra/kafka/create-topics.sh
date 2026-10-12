#!/usr/bin/env bash
# Creates every topic from blueprint sec 4.1, with the partition counts and
# retention settings specified there. Idempotent - safe to re-run.
set -euo pipefail

BOOTSTRAP="kafka:19092"
BIN=/opt/kafka/bin/kafka-topics.sh

create_topic() {
  local name=$1
  local partitions=$2
  local retention_ms=$3

  echo "Creating topic: ${name} (partitions=${partitions}, retention.ms=${retention_ms})"
  "${BIN}" --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
    --topic "${name}" \
    --partitions "${partitions}" \
    --replication-factor 1 \
    --config "retention.ms=${retention_ms}" \
    --config "cleanup.policy=delete"
}

# name                     partitions   retention
create_topic ledger-events           6   604800000    # 7 days
create_topic psp-events              6   604800000    # 7 days
create_topic bank-events             6   604800000    # 7 days
create_topic recon-results           6   2592000000   # 30 days
create_topic exception-events        3   7776000000   # 90 days
create_topic exception-events.DLT    3   7776000000   # 90 days

# Phase 2 addition: dev-only scenario-coordination topic (plain JSON, not
# Avro -- see simulation-common/ScenarioPlan.java doc). ledger-simulator
# publishes the "plan" here; psp-simulator and bank-file-ingester consume it
# to decide how to behave for that transaction_ref. Never read by
# reconciliation-engine, never registered with Schema Registry.
create_topic sim-scenarios           6   86400000     # 1 day, dev-only

# Phase 5 addition: DLQ for reconciliation-engine's MatchedResultListener
# sink (blueprint sec 6's technical-failure path, distinct from
# exception-events which carries BUSINESS exceptions). exception-events.DLT
# already covers exception-classifier's own sink; recon-results needed its
# own DLT topic added here since nothing had created one before Phase 5's
# DLQ wiring needed it (KAFKA_AUTO_CREATE_TOPICS_ENABLE=false, so this must
# be explicit).
create_topic recon-results.DLT       6   7776000000   # 90 days

echo "All topics created. Current list:"
"${BIN}" --bootstrap-server "${BOOTSTRAP}" --list
