import { Link } from 'react-router-dom';
import { TIMEZONE } from '../config';
import { formatNumber, formatDateTime, humanize } from '../lib/format';
import type { LiveStream } from '../hooks/useLiveStream';

const STATE_TEXT = {
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
} as const;

export function LivePanel({ live }: { live: LiveStream }) {
  return (
    <section aria-labelledby="live-heading">
      <h2 id="live-heading">
        Live feed{' '}
        <span className={`live-state live-${live.state}`} role="status">
          {STATE_TEXT[live.state]}
        </span>
      </h2>

      <p className="live-rate">
        <strong>{live.matchesPerSecond}</strong> matches per second over the last 10 seconds,{' '}
        {formatNumber(live.totalMatches)} since you opened this page.
        {live.droppedMatches > 0 && <> {formatNumber(live.droppedMatches)} were too fast to show individually.</>}
      </p>

      {live.exceptions.length === 0 ? (
        <p className="empty">No new exceptions since you opened this page.</p>
      ) : (
        <ul className="feed">
          {live.exceptions.map((e) => (
            <li key={e.id}>
              <Link to={`/exceptions/${e.id}`}>
                <span className="feed-ref">{e.transactionRef}</span>
                <span className="feed-code">{humanize(e.exceptionCode)}</span>
                <time dateTime={e.firstDetectedAt}>{formatDateTime(e.firstDetectedAt, TIMEZONE)}</time>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
