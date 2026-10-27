import type { EngineHealth, MetricsSummary } from '../api/types';

export const metrics: MetricsSummary = {
  date: '2026-10-06',
  totalTransactions: 1000,
  matched: 982,
  matchedWithTolerance: 12,
  exceptions: 18,
  openExceptions: 7,
  matchRatePct: 98.2,
  mismatchRatePct: 1.8,
  avgSettlementLagMs: 1500,
  settlementLagP50Ms: 900,
  settlementLagP95Ms: 4200,
  settlementLagP99Ms: 65000,
  exceptionsByCode: { MISSING_SOURCE: 11, AMOUNT_MISMATCH: 7 },
  computedAt: '2026-10-06T08:00:00Z',
  servedFrom: 'CACHE',
};

export const engineUp: EngineHealth = {
  status: 'DEGRADED',
  expectedPartitions: 6,
  partitions: [0, 1, 2, 3].map((p) => ({ partition: p, lastBeatEpochMs: Date.now(), ageMs: 2000 })),
  missingPartitions: [4, 5],
  cacheAvailable: true,
};

import type { ExceptionDetail, ExceptionSummary, Paged } from '../api/types';

export const exceptionRow = (id: number, over: Partial<ExceptionSummary> = {}): ExceptionSummary => ({
  id,
  transactionRef: `TXN-${id}`,
  exceptionCode: 'MISSING_SOURCE',
  status: 'OPEN',
  ruleName: 'MissingSourceRule',
  sourcesPresent: ['LEDGER', 'PSP'],
  firstDetectedAt: '2026-10-06T06:30:00Z',
  lastUpdatedAt: '2026-10-06T06:30:00Z',
  resolvedAt: null,
  resolvedBy: null,
  ...over,
});

export const pagedList = (items: ExceptionSummary[], over: Partial<Paged<ExceptionSummary>> = {}): Paged<ExceptionSummary> => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: 1,
  ...over,
});

export const detail: ExceptionDetail = {
  id: 7,
  candidateId: 'cand-7',
  transactionRef: 'TXN-1001',
  exceptionCode: 'AMOUNT_MISMATCH',
  status: 'OPEN',
  ruleName: 'AmountMismatchRule',
  classificationDetail: { delta: '0.50', tolerance: '0.01', amountBySource: { LEDGER: '100.0', PSP: '99.5' } },
  sourcesPresent: ['LEDGER', 'PSP'],
  events: [
    { source: 'LEDGER', eventId: 'e1', amount: 100.0, currency: 'INR' },
    { source: 'PSP', eventId: 'e2', amount: 99.5, currency: 'INR' },
  ],
  comparison: [
    { field: 'amount', valuesBySource: { LEDGER: '100.0', PSP: '99.5' }, differs: true },
    { field: 'currency', valuesBySource: { LEDGER: 'INR', PSP: 'INR' }, differs: false },
    { field: 'occurredAt', valuesBySource: { LEDGER: '2026-10-06T06:30:00Z', PSP: '2026-10-06T06:30:01Z' }, differs: true },
  ],
  firstDetectedAt: '2026-10-06T06:30:00Z',
  lastUpdatedAt: '2026-10-06T06:30:00Z',
  resolvedAt: null,
  resolvedBy: null,
  resolutionNote: null,
};
