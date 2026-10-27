import { API_BASE } from '../config';
import type {
  EngineHealth,
  ExceptionDetail,
  ExceptionQuery,
  ExceptionSummary,
  MetricsSummary,
  Paged,
  ResolveRequest,
  TransactionStatus,
} from './types';

/** status 0 = the request never reached the server. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the dashboard API. Check that dashboard-api is running.');
  }
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`.trim();
    try {
      const body = (await res.json()) as { detail?: string };
      if (body?.detail) detail = body.detail;
    } catch {
      // response was not JSON; keep the status line
    }
    throw new ApiError(res.status, detail);
  }
  return (await res.json()) as T;
}

export const LIVE_STREAM_URL = `${API_BASE}/api/v1/stream/live`;

export const api = {
  metricsSummary: (date?: string) =>
    request<MetricsSummary>(`/api/v1/metrics/summary${date ? `?date=${encodeURIComponent(date)}` : ''}`),

  engineHealth: () => request<EngineHealth>('/api/v1/engine/health'),

  transaction: (ref: string) => request<TransactionStatus>(`/api/v1/transactions/${encodeURIComponent(ref)}`),

  exceptions: (q: ExceptionQuery = {}) => {
    const params = new URLSearchParams();
    if (q.status) params.set('status', q.status);
    if (q.code) params.set('code', q.code);
    params.set('page', String(q.page ?? 0));
    params.set('size', String(q.size ?? 20));
    return request<Paged<ExceptionSummary>>(`/api/v1/exceptions?${params.toString()}`);
  },

  exception: (id: number) => request<ExceptionDetail>(`/api/v1/exceptions/${id}`),

  resolve: (id: number, body: ResolveRequest) =>
    request<ExceptionDetail>(`/api/v1/exceptions/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};
