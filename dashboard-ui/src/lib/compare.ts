import type { ExceptionDetail, FieldComparison } from '../api/types';
import { TIMEZONE } from '../config';
import { formatDateTime } from './format';

export const CORE_SOURCES = ['LEDGER', 'PSP', 'BANK'] as const;

/** Always show the three real sources, so a missing one is visible as an empty column. Extras like PSP#2 follow. */
export function comparisonColumns(comparison: FieldComparison[]): string[] {
  const keys = new Set(comparison.flatMap((f) => Object.keys(f.valuesBySource)));
  const extras = [...keys].filter((k) => !(CORE_SOURCES as readonly string[]).includes(k)).sort();
  return [...CORE_SOURCES, ...extras];
}

export function fieldLabel(field: string): string {
  return field === 'occurredAt' ? 'Occurred at' : field.charAt(0).toUpperCase() + field.slice(1);
}

export function cellText(field: string, value: string | undefined): string | null {
  if (value == null) return null;
  if (field === 'amount') {
    const n = Number(value);
    return Number.isFinite(n) ? n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : value;
  }
  if (field === 'occurredAt') return formatDateTime(value, TIMEZONE);
  return value;
}

/** "amountBySource" -> "Amount by source" */
function labelFor(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function valueText(v: unknown): string {
  if (v == null) return '—';
  if (Array.isArray(v)) return v.map(valueText).join(', ');
  if (typeof v === 'object') {
    return Object.entries(v as Record<string, unknown>)
      .map(([k, x]) => `${k}: ${valueText(x)}`)
      .join('; ');
  }
  return String(v);
}

/** What the classifier compared, as label/value rows a person can read. */
export function describeClassification(detail: ExceptionDetail['classificationDetail']) {
  return Object.entries(detail ?? {}).map(([key, value]) => ({ label: labelFor(key), value: valueText(value) }));
}
