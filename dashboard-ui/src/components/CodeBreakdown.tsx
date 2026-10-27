import { Link } from 'react-router-dom';
import type { MetricsSummary } from '../api/types';
import { formatNumber, humanize } from '../lib/format';

export function CodeBreakdown({ m }: { m: MetricsSummary }) {
  const entries = Object.entries(m.exceptionsByCode).sort((a, b) => b[1] - a[1]);
  const max = Math.max(...entries.map(([, n]) => n), 1);

  return (
    <section aria-labelledby="codes-heading">
      <h2 id="codes-heading">Exceptions by cause</h2>
      {entries.length === 0 ? (
        <p className="empty">No exceptions on this date.</p>
      ) : (
        <table className="ledger">
          <tbody>
            {entries.map(([code, n]) => (
              <tr key={code}>
                <th scope="row">
                  <Link to={`/exceptions?code=${code}`}>{humanize(code)}</Link>
                </th>
                <td className="bar-cell">
                  <span className="bar bar-bad" style={{ width: `${(n / max) * 100}%` }} />
                </td>
                <td className="num">{formatNumber(n)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="backlog">
        <Link to="/exceptions?status=OPEN">{formatNumber(m.openExceptions)} exceptions are open</Link> across all
        dates and need triage.
      </p>
    </section>
  );
}
