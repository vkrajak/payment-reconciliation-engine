const EM_DASH = '—';

export function formatNumber(n: number | null | undefined): string {
  return n == null ? EM_DASH : n.toLocaleString('en-IN');
}

export function formatPct(n: number | null | undefined): string {
  return n == null ? EM_DASH : `${n.toFixed(2)}%`;
}

/** Settlement lag arrives in milliseconds; show the unit a person would read. */
export function formatLag(ms: number | null | undefined): string {
  if (ms == null) return EM_DASH;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${(ms / 60_000).toFixed(1)} min`;
}

export function formatDateTime(iso: string | null | undefined, timeZone: string): string {
  if (!iso) return EM_DASH;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return EM_DASH;
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'medium', timeZone }).format(d);
}

export function formatTime(epochMs: number, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeStyle: 'medium', timeZone, hour12: false }).format(new Date(epochMs));
}

/** yyyy-MM-dd for "now" in the given timezone (the API takes ISO dates). */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

export function formatDateLong(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  if (!y || !m || !d) return isoDate;
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, d)));
}

export function formatAge(ms: number): string {
  if (ms < 1000) return 'just now';
  if (ms < 60_000) return `${Math.round(ms / 1000)} s ago`;
  return `${Math.round(ms / 60_000)} min ago`;
}

/** MATCHED_WITH_TOLERANCE -> "Matched with tolerance" */
export function humanize(code: string): string {
  const s = code.toLowerCase().replace(/_/g, ' ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}
