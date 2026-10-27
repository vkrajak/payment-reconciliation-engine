import type { MetricsSummary } from '../api/types';
import { formatLag } from '../lib/format';

export function LagTable({ m }: { m: MetricsSummary }) {
  const rows = [
    { label: 'Median (p50)', value: m.settlementLagP50Ms },
    { label: 'p95', value: m.settlementLagP95Ms },
    { label: 'p99', value: m.settlementLagP99Ms },
    { label: 'Average', value: m.avgSettlementLagMs },
  ];
  const max = Math.max(...rows.map((r) => r.value ?? 0), 1);
  const hasData = rows.some((r) => r.value != null);

  return (
    <section aria-labelledby="lag-heading">
      <h2 id="lag-heading">Settlement lag</h2>
      {!hasData ? (
        <p className="empty">Lag is measured when a transaction is matched. None matched for this date yet.</p>
      ) : (
        <table className="ledger">
          <tbody>
            {rows.map((r) => (
              <tr key={r.label}>
                <th scope="row">{r.label}</th>
                <td className="bar-cell">
                  <span className="bar" style={{ width: `${((r.value ?? 0) / max) * 100}%` }} />
                </td>
                <td className="num">{formatLag(r.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
