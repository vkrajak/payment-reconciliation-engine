// Mirrors the dashboard-api (Phase 6) responses.

export type ServedFrom = 'CACHE' | 'DATABASE';

export interface MetricsSummary {
  date: string;
  totalTransactions: number;
  matched: number;
  matchedWithTolerance: number;
  exceptions: number;
  openExceptions: number;
  matchRatePct: number;
  mismatchRatePct: number;
  avgSettlementLagMs: number | null;
  settlementLagP50Ms: number | null;
  settlementLagP95Ms: number | null;
  settlementLagP99Ms: number | null;
  exceptionsByCode: Record<string, number>;
  computedAt: string;
  servedFrom: ServedFrom;
}

export type EngineStatus = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

export interface PartitionBeat {
  partition: number;
  lastBeatEpochMs: number;
  ageMs: number;
}

export interface EngineHealth {
  status: EngineStatus;
  expectedPartitions: number;
  partitions: PartitionBeat[];
  missingPartitions: number[];
  cacheAvailable: boolean;
}

export type TxnStatus = 'MATCHED' | 'MATCHED_WITH_TOLERANCE' | 'EXCEPTION' | 'RESOLVED';

export interface TransactionStatus {
  transactionRef: string;
  status: TxnStatus;
  exceptionCode: string | null;
  matchedAt: string | null;
  updatedAt: string | null;
  sources: string[];
  servedFrom: ServedFrom;
}

export type ExceptionStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

export const EXCEPTION_CODES = [
  'MISSING_SOURCE',
  'AMOUNT_MISMATCH',
  'CURRENCY_MISMATCH',
  'DUPLICATE_TRANSACTION_REF',
  'ORPHAN_EVENT',
  'LATE_ARRIVAL',
  'UNCLASSIFIED',
] as const;

export interface ExceptionSummary {
  id: number;
  transactionRef: string;
  exceptionCode: string;
  status: ExceptionStatus;
  ruleName: string;
  sourcesPresent: string[];
  firstDetectedAt: string;
  lastUpdatedAt: string | null;
  resolvedAt: string | null;
  resolvedBy: string | null;
}

export interface Paged<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ExceptionQuery {
  status?: string;
  code?: string;
  page?: number;
  size?: number;
}

export interface FieldComparison {
  field: string;
  valuesBySource: Record<string, string>;
  differs: boolean;
}

export interface ExceptionDetail extends Omit<ExceptionSummary, 'sourcesPresent'> {
  candidateId: string;
  classificationDetail: Record<string, unknown> | null;
  sourcesPresent: string[];
  events: Array<Record<string, unknown>>;
  comparison: FieldComparison[];
  resolutionNote: string | null;
}

export interface ResolveRequest {
  resolvedBy?: string;
  note: string;
}

/** Payload of the SSE "matches" event: one batch per flush, never one event per match. */
export interface MatchBatch {
  count: number;
  dropped: number;
  sample: unknown[];
}
