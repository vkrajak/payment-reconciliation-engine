import { useQuery } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { ApiError, api } from '../api/client';
import { ErrorBanner } from '../components/ErrorBanner';
import { StatusBadge } from '../components/StatusBadge';
import { TIMEZONE } from '../config';
import { formatDateTime, humanize } from '../lib/format';

export default function LookupPage() {
  const [input, setInput] = useState('');
  const [ref, setRef] = useState('');

  const result = useQuery({
    queryKey: ['transaction', ref],
    queryFn: () => api.transaction(ref),
    enabled: ref !== '',
    retry: false,
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setRef(input.trim());
  };

  const notFound = result.error instanceof ApiError && result.error.status === 404;

  return (
    <>
      <div className="page-head">
        <h1>Find a transaction</h1>
      </div>

      <form className="lookup-form" onSubmit={submit}>
        <label htmlFor="txn-ref">Transaction reference</label>
        <div className="lookup-row">
          <input
            id="txn-ref"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="TXN-1001"
            autoComplete="off"
            spellCheck={false}
          />
          <button type="submit" className="btn" disabled={input.trim() === ''}>
            Find transaction
          </button>
        </div>
      </form>

      {result.isFetching && <p className="empty">Looking up {ref}…</p>}
      {notFound && (
        <p className="empty">
          No transaction found for “{ref}”. References are case-sensitive; check for extra characters.
        </p>
      )}
      {result.isError && !notFound && <ErrorBanner error={result.error} onRetry={() => void result.refetch()} />}

      {result.data && !result.isFetching && (
        <section aria-labelledby="txn-heading" className="txn-result">
          <h2 id="txn-heading">
            {result.data.transactionRef} <StatusBadge status={result.data.status} />
          </h2>
          <dl className="facts">
            {result.data.exceptionCode && (
              <div>
                <dt>Cause</dt>
                <dd>{humanize(result.data.exceptionCode)}</dd>
              </div>
            )}
            <div>
              <dt>Seen by</dt>
              <dd>{result.data.sources.length ? result.data.sources.join(', ') : '—'}</dd>
            </div>
            <div>
              <dt>Matched at</dt>
              <dd>{formatDateTime(result.data.matchedAt, TIMEZONE)}</dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>{formatDateTime(result.data.updatedAt, TIMEZONE)}</dd>
            </div>
          </dl>
          <p className="provenance">
            {result.data.servedFrom === 'CACHE' ? 'Read from the live cache.' : 'Read from the database.'}
          </p>
        </section>
      )}
    </>
  );
}
