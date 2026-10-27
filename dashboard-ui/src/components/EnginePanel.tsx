import type { EngineHealth } from '../api/types';
import { formatAge } from '../lib/format';
import { StatusBadge } from './StatusBadge';

const EXPLANATION: Record<string, string> = {
  UP: 'Every partition is being processed.',
  DEGRADED: 'Some partitions have no heartbeat. Matching for those transactions is not progressing.',
  DOWN: 'No heartbeats. The reconciliation engine is stopped or cannot reach Redis.',
  UNKNOWN: 'Redis is unavailable, so engine heartbeats cannot be read.',
};

export function EnginePanel({ health, loading, error }: { health?: EngineHealth; loading: boolean; error: unknown }) {
  return (
    <section aria-labelledby="engine-heading">
      <h2 id="engine-heading">Reconciliation engine</h2>
      {loading && !health && <p className="empty">Checking heartbeats…</p>}
      {error != null && !health && <p className="empty">Engine status is unavailable while the API is unreachable.</p>}
      {health && (
        <>
          <p className="engine-status">
            <StatusBadge status={health.status} /> <span>{EXPLANATION[health.status]}</span>
          </p>
          {health.cacheAvailable && (
            <ol className="partitions" aria-label="Partition heartbeats">
              {Array.from({ length: health.expectedPartitions }, (_, p) => {
                const beat = health.partitions.find((b) => b.partition === p);
                return (
                  <li
                    key={p}
                    className={beat ? 'partition partition-up' : 'partition partition-missing'}
                    title={beat ? `Partition ${p}: ${formatAge(beat.ageMs)}` : `Partition ${p}: no heartbeat`}
                  >
                    <span className="visually-hidden">Partition </span>
                    {p}
                    <span className="visually-hidden">{beat ? ' is beating' : ' has no heartbeat'}</span>
                  </li>
                );
              })}
            </ol>
          )}
        </>
      )}
    </section>
  );
}
