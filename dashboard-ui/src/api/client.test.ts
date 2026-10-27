import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api } from './client';
import { mockFetch } from '../test/fakes';

afterEach(() => vi.unstubAllGlobals());

describe('api client', () => {
  it('builds the exceptions query and omits empty filters', async () => {
    const fetchMock = mockFetch({ '/exceptions': { body: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } } });

    await api.exceptions({ status: 'OPEN', code: '', page: 2 });

    const url = String(fetchMock.mock.calls[0]![0]);
    expect(url).toContain('status=OPEN');
    expect(url).toContain('page=2');
    expect(url).toContain('size=20');
    expect(url).not.toContain('code=');
  });

  it('posts the resolution as JSON', async () => {
    const fetchMock = mockFetch({ '/exceptions/7/resolve': { body: {} } });

    await api.resolve(7, { resolvedBy: 'asha', note: 'done' });

    const [, init] = fetchMock.mock.calls[0]! as unknown as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({ resolvedBy: 'asha', note: 'done' });
  });

  it('uses the server problem detail, and falls back to the status line for non-JSON errors', async () => {
    mockFetch({ '/transactions/A': { status: 404, body: { detail: 'Transaction A not found' } } });
    await expect(api.transaction('A')).rejects.toMatchObject({ status: 404, message: 'Transaction A not found' });

    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>bad gateway</html>', { status: 502, statusText: 'Bad Gateway' })));
    await expect(api.engineHealth()).rejects.toMatchObject({ status: 502, message: '502 Bad Gateway' });
  });

  it('reports an unreachable API as status 0 with a plain message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));

    const err = await api.engineHealth().catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(0);
    expect((err as ApiError).message).toMatch(/Cannot reach the dashboard API/);
  });

  it('encodes transaction references', async () => {
    const fetchMock = mockFetch({ '/transactions/': { body: {} } });
    await api.transaction('TXN/1 2');
    expect(String(fetchMock.mock.calls[0]![0])).toContain('/transactions/TXN%2F1%202');
  });
});
