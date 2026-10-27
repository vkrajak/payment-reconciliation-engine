import { describe, expect, it } from 'vitest';
import { formatAge, formatDateLong, formatLag, formatNumber, formatPct, humanize, todayIn } from './format';

describe('format', () => {
  it('shows lag in the unit a person would read', () => {
    expect(formatLag(null)).toBe('—');
    expect(formatLag(850)).toBe('850 ms');
    expect(formatLag(1500)).toBe('1.5 s');
    expect(formatLag(65_000)).toBe('1.1 min');
  });

  it('formats percentages and numbers', () => {
    expect(formatPct(98.2)).toBe('98.20%');
    expect(formatPct(null)).toBe('—');
    expect(formatNumber(1234567)).toBe('12,34,567'); // en-IN grouping
    expect(formatNumber(undefined)).toBe('—');
  });

  it('works out "today" in the configured timezone, not the browser zone', () => {
    const instant = new Date('2026-10-06T20:00:00Z'); // 01:30 on the 7th in Kolkata, still the 6th in UTC
    expect(todayIn('Asia/Kolkata', instant)).toBe('2026-10-07');
    expect(todayIn('UTC', instant)).toBe('2026-10-06');
  });

  it('formats long dates and ages and humanizes codes', () => {
    expect(formatDateLong('2026-10-06')).toBe('6 October 2026');
    expect(formatAge(500)).toBe('just now');
    expect(formatAge(12_000)).toBe('12 s ago');
    expect(humanize('MATCHED_WITH_TOLERANCE')).toBe('Matched with tolerance');
  });
});
