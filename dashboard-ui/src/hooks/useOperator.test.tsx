import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { setOperator, useOperator } from './useOperator';

describe('useOperator', () => {
  beforeEach(() => {
    localStorage.clear();
    act(() => setOperator(''));
  });

  it('trims, remembers in this browser and updates every component using it', () => {
    const a = renderHook(() => useOperator());
    const b = renderHook(() => useOperator());

    act(() => a.result.current[1]('  asha.k  '));

    expect(a.result.current[0]).toBe('asha.k');
    expect(b.result.current[0]).toBe('asha.k');
    expect(localStorage.getItem('recon.operator')).toBe('asha.k');
  });
});
