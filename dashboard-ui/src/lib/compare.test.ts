import { describe, expect, it } from 'vitest';
import { cellText, comparisonColumns, describeClassification, fieldLabel } from './compare';

describe('compare helpers', () => {
  it('always shows the three sources, then extras such as a second PSP event', () => {
    const cols = comparisonColumns([
      { field: 'amount', valuesBySource: { LEDGER: '1', PSP: '1', 'PSP#2': '2' }, differs: true },
    ]);
    expect(cols).toEqual(['LEDGER', 'PSP', 'BANK', 'PSP#2']);
    expect(comparisonColumns([])).toEqual(['LEDGER', 'PSP', 'BANK']);
  });

  it('formats amounts with two decimals and leaves unknown values alone', () => {
    expect(cellText('amount', '100.0')).toBe('100.00');
    expect(cellText('amount', '1234567.5')).toBe('12,34,567.50');
    expect(cellText('amount', 'n/a')).toBe('n/a');
    expect(cellText('currency', 'INR')).toBe('INR');
    expect(cellText('amount', undefined)).toBeNull();
  });

  it('labels fields and explains the classification in words', () => {
    expect(fieldLabel('occurredAt')).toBe('Occurred at');
    expect(fieldLabel('amount')).toBe('Amount');
    expect(
      describeClassification({ amountBySource: { LEDGER: '100.0', PSP: '99.5' }, missing: ['BANK'], delta: '0.50' }),
    ).toEqual([
      { label: 'Amount by source', value: 'LEDGER: 100.0; PSP: 99.5' },
      { label: 'Missing', value: 'BANK' },
      { label: 'Delta', value: '0.50' },
    ]);
    expect(describeClassification(null)).toEqual([]);
  });
});
