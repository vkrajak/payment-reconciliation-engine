import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { ExceptionDetail } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { StatusBadge } from '../components/StatusBadge';
import { TIMEZONE } from '../config';
import { useOperator } from '../hooks/useOperator';
import { cellText, comparisonColumns, describeClassification, fieldLabel } from '../lib/compare';
import { formatDateTime, humanize } from '../lib/format';

export default function ExceptionDetailPage() {
  const { id: rawId } = useParams();
  const id = Number(rawId);
  const valid = Number.isInteger(id) && id > 0;

  const detail = useQuery({
    queryKey: ['exception', id],
    queryFn: () => api.exception(id),
    enabled: valid,
    retry: false,
  });

  if (!valid) return <Missing text="That exception number is not valid." />;
  if (detail.isLoading) return <p className="empty">Loading exception…</p>;
  if (detail.error instanceof ApiError && detail.error.status === 404) {
    return <Missing text={`Exception ${id} does not exist.`} />;
  }
  if (detail.isError) return <ErrorBanner error={detail.error} onRetry={() => void detail.refetch()} />;
  if (!detail.data) return null;

  return <Detail e={detail.data} />;
}

function Missing({ text }: { text: string }) {
  return (
    <>
      <div className="page-head">
        <h1>Exception not found</h1>
      </div>
      <p className="empty">
        {text} <Link to="/exceptions">Back to the list</Link>.
      </p>
    </>
  );
}

function Detail({ e }: { e: ExceptionDetail }) {
  const columns = comparisonColumns(e.comparison);
  const classification = describeClassification(e.classificationDetail);

  return (
    <>
      <p className="crumb">
        <Link to="/exceptions">Exceptions</Link>
      </p>
      <div className="page-head">
        <h1>
          {e.transactionRef} <StatusBadge status={e.status} />
        </h1>
        <p className="count">{humanize(e.exceptionCode)}</p>
      </div>

      <div className="detail-grid">
        <div className="col-main">
          <section aria-labelledby="compare-heading" className="panel">
            <h2 id="compare-heading">What each source reported</h2>
            <div className="table-wrap">
              <table className="table compare">
                <thead>
                  <tr>
                    <th scope="col">Field</th>
                    {columns.map((c) => (
                      <th scope="col" key={c}>
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {e.comparison.map((f) => (
                    <tr key={f.field} className={f.differs ? 'differs' : undefined}>
                      <th scope="row">
                        {fieldLabel(f.field)}
                        {f.differs && <span className="differs-note"> differs</span>}
                      </th>
                      {columns.map((c) => {
                        const text = cellText(f.field, f.valuesBySource[c]);
                        return (
                          <td key={c} className={text == null ? 'absent' : undefined}>
                            {text ?? 'No event'}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <details className="raw">
              <summary>Raw events ({e.events.length})</summary>
              <pre>{JSON.stringify(e.events, null, 2)}</pre>
            </details>
          </section>

          <section aria-labelledby="why-heading" className="panel">
            <h2 id="why-heading">Why it was classified this way</h2>
            <p className="rule-line">
              Rule <code>{e.ruleName}</code> decided this was {humanize(e.exceptionCode).toLowerCase()}.
            </p>
            {classification.length > 0 && (
              <dl className="facts">
                {classification.map((row) => (
                  <div key={row.label}>
                    <dt>{row.label}</dt>
                    <dd>{row.value}</dd>
                  </div>
                ))}
              </dl>
            )}
          </section>
        </div>

        <aside className="col-side">
          <section aria-labelledby="facts-heading" className="panel">
            <h2 id="facts-heading">Details</h2>
            <dl className="facts">
              <div>
                <dt>Seen by</dt>
                <dd>{e.sourcesPresent.join(', ') || '—'}</dd>
              </div>
              <div>
                <dt>Detected</dt>
                <dd>{formatDateTime(e.firstDetectedAt, TIMEZONE)}</dd>
              </div>
              <div>
                <dt>Last updated</dt>
                <dd>{formatDateTime(e.lastUpdatedAt, TIMEZONE)}</dd>
              </div>
              <div>
                <dt>Candidate</dt>
                <dd className="break">{e.candidateId}</dd>
              </div>
            </dl>
          </section>

          {e.status === 'RESOLVED' ? <Resolution e={e} /> : <ResolveForm e={e} />}
        </aside>
      </div>
    </>
  );
}

function Resolution({ e }: { e: ExceptionDetail }) {
  return (
    <section aria-labelledby="resolution-heading" className="panel">
      <h2 id="resolution-heading">Resolved</h2>
      <dl className="facts">
        <div>
          <dt>By</dt>
          <dd>{e.resolvedBy ?? '—'}</dd>
        </div>
        <div>
          <dt>At</dt>
          <dd>{formatDateTime(e.resolvedAt, TIMEZONE)}</dd>
        </div>
        <div>
          <dt>Note</dt>
          <dd className="note">{e.resolutionNote ?? '—'}</dd>
        </div>
      </dl>
    </section>
  );
}

function ResolveForm({ e }: { e: ExceptionDetail }) {
  const queryClient = useQueryClient();
  const [operator, rememberOperator] = useOperator();
  const [name, setName] = useState(operator);
  const [note, setNote] = useState('');

  const resolve = useMutation({
    mutationFn: () => api.resolve(e.id, { resolvedBy: name.trim(), note: note.trim() }),
    onSuccess: (updated) => {
      rememberOperator(name);
      queryClient.setQueryData(['exception', e.id], updated);
      void queryClient.invalidateQueries({ queryKey: ['exceptions'] });
      void queryClient.invalidateQueries({ queryKey: ['metrics'] });
      void queryClient.invalidateQueries({ queryKey: ['transaction'] });
    },
    onError: (err) => {
      // Someone else resolved it first: reload so the page shows their resolution.
      if (err instanceof ApiError && err.status === 409) {
        void queryClient.invalidateQueries({ queryKey: ['exception', e.id] });
      }
    },
  });

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    resolve.mutate();
  };
  const conflict = resolve.error instanceof ApiError && resolve.error.status === 409;
  const ready = name.trim() !== '' && note.trim() !== '' && !resolve.isPending;

  return (
    <section aria-labelledby="resolve-heading" className="panel">
      <h2 id="resolve-heading">Resolve this exception</h2>
      <form className="resolve-form" onSubmit={submit}>
        <label htmlFor="resolved-by">Your name</label>
        <input id="resolved-by" value={name} onChange={(ev) => setName(ev.target.value)} maxLength={100} />

        <label htmlFor="resolution-note">What was done</label>
        <textarea
          id="resolution-note"
          rows={4}
          value={note}
          onChange={(ev) => setNote(ev.target.value)}
          maxLength={2000}
          placeholder="For example: bank confirmed the credit; ledger corrected to 100.00."
        />

        {conflict && (
          <p className="form-error" role="alert">
            Someone resolved this exception while you were reading it. The page now shows their resolution.
          </p>
        )}
        {resolve.isError && !conflict && <ErrorBanner error={resolve.error} />}

        <button type="submit" className="btn" disabled={!ready}>
          {resolve.isPending ? 'Resolving…' : 'Resolve exception'}
        </button>
      </form>
    </section>
  );
}
