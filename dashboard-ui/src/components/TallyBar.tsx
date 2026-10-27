import type { MetricsSummary } from '../api/types';
import { formatDateLong, formatNumber, formatPct } from '../lib/format';

/** The page's centrepiece: every transaction of the day as one ruled bar, split matched vs exception. */
export function TallyBar({ m }: { m: MetricsSummary }) {
  const exact = Math.max(m.matched - m.matchedWithTolerance, 0);
  const total = m.totalTransactions;
  const width = (n: number) => (total > 0 ? `${(n / total) * 100}%` : '0%');

  return (
    <section aria-labelledby="tally-heading" className="tally">
      <h2 id="tally-heading" className="visually-hidden">
        Matched and exception totals
      </h2>
      <p className="tally-figure">
        <span className="tally-number">{formatPct(m.matchRatePct)}</span>
        <span className="tally-caption">
          matched, out of {formatNumber(total)} transactions on {formatDateLong(m.date)}
        </span>
      </p>

      {total === 0 ? (
        <p className="empty">No transactions have been reconciled for this date yet.</p>
      ) : (
        <>
          <div
            className="tally-bar"
            role="img"
            aria-label={`${formatNumber(exact)} matched, ${formatNumber(m.matchedWithTolerance)} matched within tolerance, ${formatNumber(m.exceptions)} in exception`}
          >
            <span className="seg seg-match" style={{ width: width(exact) }} />
            <span className="seg seg-tol" style={{ width: width(m.matchedWithTolerance) }} />
            <span className="seg seg-bad" style={{ width: width(m.exceptions) }} />
          </div>
          <dl className="tally-legend">
            <div>
              <dt>
                <i className="key key-match" /> Matched
              </dt>
              <dd>{formatNumber(exact)}</dd>
            </div>
            <div>
              <dt>
                <i className="key key-tol" /> Matched within tolerance
              </dt>
              <dd>{formatNumber(m.matchedWithTolerance)}</dd>
            </div>
            <div>
              <dt>
                <i className="key key-bad" /> In exception ({formatPct(m.mismatchRatePct)})
              </dt>
              <dd>{formatNumber(m.exceptions)}</dd>
            </div>
          </dl>
        </>
      )}
    </section>
  );
}
