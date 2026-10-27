import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExceptionSummary } from '../api/types';
import { FakeEventSource } from '../test/fakes';
import { useLiveStream } from './useLiveStream';

const ex = (id: number): ExceptionSummary => ({
  id,
  transactionRef: `TXN-${id}`,
  exceptionCode: 'MISSING_SOURCE',
  status: 'OPEN',
  ruleName: 'MissingSourceRule',
  sourcesPresent: ['LEDGER'],
  firstDetectedAt: '2026-10-06T08:00:00Z',
  lastUpdatedAt: null,
  resolvedAt: null,
  resolvedBy: null,
});

const setup = (opts = {}) =>
  renderHook(() =>
    useLiveStream({ createSource: (u) => new FakeEventSource(u) as unknown as EventSource, ...opts }),
  );

describe('useLiveStream', () => {
  beforeEach(() => {
    FakeEventSource.reset();
    vi.useFakeTimers();
  });
  afterEach(() => vi.useRealTimers());

  it('moves connecting -> live -> reconnecting and back', () => {
    const { result } = setup();
    expect(result.current.state).toBe('connecting');

    act(() => FakeEventSource.latest().onopen?.());
    expect(result.current.state).toBe('live');

    act(() => FakeEventSource.latest().onerror?.());
    expect(result.current.state).toBe('reconnecting');

    act(() => FakeEventSource.latest().emit('connected', { clients: 1 }));
    expect(result.current.state).toBe('live');
  });

  it('puts new exceptions first, dedupes by id and caps the list', () => {
    const { result } = setup({ maxExceptions: 2 });
    act(() => {
      const s = FakeEventSource.latest();
      s.emit('exception', ex(1));
      s.emit('exception', ex(2));
      s.emit('exception', ex(1)); // same id again
      s.emit('exception', ex(3));
    });
    expect(result.current.exceptions.map((e) => e.id)).toEqual([3, 1]);
  });

  it('accumulates match batches and reports a rate over the window', () => {
    const { result } = setup({ rateWindowMs: 10_000 });
    act(() => {
      FakeEventSource.latest().emit('matches', { count: 40, dropped: 5, sample: [] });
      FakeEventSource.latest().emit('matches', { count: 60, dropped: 0, sample: [] });
    });
    expect(result.current.totalMatches).toBe(100);
    expect(result.current.droppedMatches).toBe(5);

    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.matchesPerSecond).toBe(10); // 100 matches / 10 s

    act(() => vi.advanceTimersByTime(11_000)); // batches age out of the window
    expect(result.current.matchesPerSecond).toBe(0);
  });

  it('ignores malformed events and closes the stream on unmount', () => {
    const { result, unmount } = setup();
    act(() => {
      FakeEventSource.latest().emit('exception', null);
      FakeEventSource.latest().emit('matches', null);
    });
    expect(result.current.exceptions).toEqual([]);
    expect(result.current.totalMatches).toBe(0);

    const source = FakeEventSource.latest();
    unmount();
    expect(source.closed).toBe(true);
  });
});
