import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import { CodeBreakdown } from '../components/CodeBreakdown';
import { EnginePanel } from '../components/EnginePanel';
import { ErrorBanner } from '../components/ErrorBanner';
import { LagTable } from '../components/LagTable';
import { LivePanel } from '../components/LivePanel';
import { TallyBar } from '../components/TallyBar';
import { TIMEZONE } from '../config';
import { useLiveStream } from '../hooks/useLiveStream';
import { formatDateTime, todayIn } from '../lib/format';

export default function OverviewPage() {
  const [date, setDate] = useState(() => todayIn(TIMEZONE));
  const isToday = date === todayIn(TIMEZONE);

  const metrics = useQuery({
    queryKey: ['metrics', date],
    queryFn: () => api.metricsSummary(date),
    refetchInterval: isToday ? 30_000 : false, // the server caches for 5 minutes anyway
  });
  const engine = useQuery({
    queryKey: ['engine'],
    queryFn: () => api.engineHealth(),
    refetchInterval: 10_000,
  });
  const live = useLiveStream();

  return (
    <>
      <div className="page-head">
        <h1>Overview</h1>
        <label className="field-inline">
          Date
          <input
            type="date"
            value={date}
            max={todayIn(TIMEZONE)}
            onChange={(e) => e.target.value && setDate(e.target.value)}
          />
        </label>
      </div>

      {metrics.isError && <ErrorBanner error={metrics.error} onRetry={() => void metrics.refetch()} />}

      <div className="overview-grid">
        <div className="col-main">
          {metrics.isLoading && <p className="empty">Loading figures…</p>}
          {metrics.data && (
            <>
              <TallyBar m={metrics.data} />
              <LagTable m={metrics.data} />
              <CodeBreakdown m={metrics.data} />
              <p className="provenance">
                Calculated {formatDateTime(metrics.data.computedAt, TIMEZONE)}
                {metrics.data.servedFrom === 'CACHE' ? ' and cached for up to 5 minutes.' : ' from the database just now.'}
              </p>
            </>
          )}
        </div>
        <aside className="col-side">
          <EnginePanel health={engine.data} loading={engine.isLoading} error={engine.error} />
          <LivePanel live={live} />
        </aside>
      </div>
    </>
  );
}
