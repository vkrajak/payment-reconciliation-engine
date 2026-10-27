import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { EXCEPTION_CODES } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { StatusBadge } from '../components/StatusBadge';
import { TIMEZONE } from '../config';
import { useLiveStream } from '../hooks/useLiveStream';
import { formatDateTime, formatNumber, humanize } from '../lib/format';

const PAGE_SIZE = 20;
const STATUS_OPTIONS = [
  { value: 'OPEN', label: 'Open' },
  { value: 'ACKNOWLEDGED', label: 'Acknowledged' },
  { value: 'RESOLVED', label: 'Resolved' },
  { value: 'ALL', label: 'All statuses' },
];

export default function ExceptionsPage() {
  const [params, setParams] = useSearchParams();
  // Triage means "what still needs me", so Open is the default when the address has no status.
  const status = params.get('status') ?? 'OPEN';
  const code = params.get('code') ?? '';
  const page = Math.max(0, Number(params.get('page')) || 0);

  const list = useQuery({
    queryKey: ['exceptions', status, code, page],
    queryFn: () =>
      api.exceptions({ status: status === 'ALL' ? undefined : status, code: code || undefined, page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  // New exceptions arrive over the live stream; offer them instead of reshuffling rows under the reader.
  const live = useLiveStream({ maxExceptions: 100 });
  const [seenUpTo, setSeenUpTo] = useState(0);
  const shown = new Set(list.data?.items.map((i) => i.id));
  const fresh = live.exceptions.filter(
    (e) =>
      e.id > seenUpTo &&
      !shown.has(e.id) &&
      (status === 'ALL' || e.status === status) &&
      (code === '' || e.exceptionCode === code),
  );

  const change = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setParams(next);
  };
  const setFilter = (key: 'status' | 'code', value: string) => change({ [key]: value || null, page: null });
  const goToPage = (p: number) => change({ page: p > 0 ? String(p) : null });

  const showNew = () => {
    setSeenUpTo(Math.max(0, ...live.exceptions.map((e) => e.id)));
    if (page !== 0) goToPage(0);
    else void list.refetch();
  };

  const data = list.data;
  const totalPages = Math.max(data?.totalPages ?? 1, 1);

  return (
    <>
      <div className="page-head">
        <h1>Exceptions</h1>
        {data && <p className="count">{formatNumber(data.totalElements)} matching</p>}
      </div>

      <form className="filters" onSubmit={(e) => e.preventDefault()}>
        <label>
          Status
          <select value={status} onChange={(e) => setFilter('status', e.target.value)}>
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Cause
          <select value={code} onChange={(e) => setFilter('code', e.target.value)}>
            <option value="">All causes</option>
            {EXCEPTION_CODES.map((c) => (
              <option key={c} value={c}>
                {humanize(c)}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="btn btn-quiet" onClick={() => void list.refetch()} disabled={list.isFetching}>
          {list.isFetching ? 'Refreshing…' : 'Refresh'}
        </button>
      </form>

      {fresh.length > 0 && (
        <div className="banner banner-info" role="status">
          <span>
            {fresh.length} new {fresh.length === 1 ? 'exception' : 'exceptions'} arrived since you opened this list.
          </span>
          <button type="button" className="btn btn-quiet" onClick={showNew}>
            Show {fresh.length === 1 ? 'it' : 'them'}
          </button>
        </div>
      )}

      {list.isError && <ErrorBanner error={list.error} onRetry={() => void list.refetch()} />}
      {list.isLoading && <p className="empty">Loading exceptions…</p>}

      {data && data.items.length === 0 && (
        <p className="empty">
          {status === 'OPEN' && code === ''
            ? 'Nothing is waiting for triage.'
            : 'No exceptions match these filters. Try All statuses or All causes.'}
        </p>
      )}

      {data && data.items.length > 0 && (
        <>
          <div className="table-wrap">
            <table className="table">
              <caption className="visually-hidden">Exceptions, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Transaction</th>
                  <th scope="col">Cause</th>
                  <th scope="col">Status</th>
                  <th scope="col">Seen by</th>
                  <th scope="col">Detected</th>
                  <th scope="col">Resolved by</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr key={e.id}>
                    <th scope="row">
                      <Link to={`/exceptions/${e.id}`}>{e.transactionRef}</Link>
                    </th>
                    <td>{humanize(e.exceptionCode)}</td>
                    <td>
                      <StatusBadge status={e.status} />
                    </td>
                    <td>{e.sourcesPresent.join(', ') || '—'}</td>
                    <td>{formatDateTime(e.firstDetectedAt, TIMEZONE)}</td>
                    <td>{e.resolvedBy ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <nav className="pager" aria-label="Pagination">
            <button type="button" className="btn btn-quiet" disabled={page === 0} onClick={() => goToPage(page - 1)}>
              Previous
            </button>
            <span aria-live="polite">
              Page {page + 1} of {totalPages}
            </span>
            <button
              type="button"
              className="btn btn-quiet"
              disabled={page + 1 >= totalPages}
              onClick={() => goToPage(page + 1)}
            >
              Next
            </button>
          </nav>
        </>
      )}
    </>
  );
}
