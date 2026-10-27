import { useEffect, useRef, useState } from 'react';
import { LIVE_STREAM_URL } from '../api/client';
import type { ExceptionSummary, MatchBatch } from '../api/types';

export type StreamState = 'connecting' | 'live' | 'reconnecting';

export interface LiveStream {
  state: StreamState;
  /** Newest first, capped at maxExceptions. */
  exceptions: ExceptionSummary[];
  /** Matches seen since this page was opened. */
  totalMatches: number;
  /** Matches the server dropped because a batch backed up. */
  droppedMatches: number;
  matchesPerSecond: number;
}

interface Options {
  url?: string;
  maxExceptions?: number;
  rateWindowMs?: number;
  /** Test seam; defaults to the browser EventSource. */
  createSource?: (url: string) => EventSource;
}

function parse<T>(e: Event): T | null {
  try {
    return JSON.parse((e as MessageEvent).data as string) as T;
  } catch {
    return null;
  }
}

/**
 * Subscribes to GET /api/v1/stream/live. EventSource reconnects by itself (the server ends each
 * stream after 30 minutes), so errors only flip the state to "reconnecting".
 */
export function useLiveStream(options: Options = {}): LiveStream {
  const { url = LIVE_STREAM_URL, maxExceptions = 25, rateWindowMs = 10_000 } = options;
  const createSourceRef = useRef(options.createSource ?? ((u: string) => new EventSource(u)));
  createSourceRef.current = options.createSource ?? createSourceRef.current;

  const [state, setState] = useState<StreamState>('connecting');
  const [exceptions, setExceptions] = useState<ExceptionSummary[]>([]);
  const [totalMatches, setTotalMatches] = useState(0);
  const [droppedMatches, setDroppedMatches] = useState(0);
  const [matchesPerSecond, setMatchesPerSecond] = useState(0);
  const batches = useRef<Array<{ at: number; count: number }>>([]);

  useEffect(() => {
    const source = createSourceRef.current(url);
    setState('connecting');

    source.onopen = () => setState('live');
    source.onerror = () => setState('reconnecting');
    source.addEventListener('connected', () => setState('live'));

    source.addEventListener('exception', (e) => {
      const ex = parse<ExceptionSummary>(e);
      if (!ex) return;
      setExceptions((prev) => [ex, ...prev.filter((x) => x.id !== ex.id)].slice(0, maxExceptions));
    });

    source.addEventListener('matches', (e) => {
      const batch = parse<MatchBatch>(e);
      if (!batch) return;
      batches.current.push({ at: Date.now(), count: batch.count });
      setTotalMatches((n) => n + batch.count);
      if (batch.dropped) setDroppedMatches((n) => n + batch.dropped);
    });

    const timer = setInterval(() => {
      const cutoff = Date.now() - rateWindowMs;
      batches.current = batches.current.filter((b) => b.at >= cutoff);
      const sum = batches.current.reduce((acc, b) => acc + b.count, 0);
      const rate = Math.round((sum / (rateWindowMs / 1000)) * 10) / 10;
      setMatchesPerSecond((prev) => (prev === rate ? prev : rate));
    }, 1000);

    return () => {
      source.close();
      clearInterval(timer);
    };
  }, [url, maxExceptions, rateWindowMs]);

  return { state, exceptions, totalMatches, droppedMatches, matchesPerSecond };
}
