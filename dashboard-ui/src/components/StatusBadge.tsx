import { humanize } from '../lib/format';

type Tone = 'good' | 'bad' | 'warn' | 'neutral';

const TONES: Record<string, Tone> = {
  MATCHED: 'good',
  MATCHED_WITH_TOLERANCE: 'good',
  UP: 'good',
  EXCEPTION: 'bad',
  OPEN: 'bad',
  DOWN: 'bad',
  DEGRADED: 'warn',
  ACKNOWLEDGED: 'warn',
  RESOLVED: 'neutral',
  UNKNOWN: 'neutral',
};

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${TONES[status] ?? 'neutral'}`}>{humanize(status)}</span>;
}
