import { useSyncExternalStore } from 'react';

const KEY = 'recon.operator';
const listeners = new Set<() => void>();

function load(): string {
  try {
    return localStorage.getItem(KEY) ?? '';
  } catch {
    return ''; // storage blocked: the name just lasts for this page view
  }
}

let current = load();

export function setOperator(name: string): void {
  current = name.trim();
  try {
    localStorage.setItem(KEY, current);
  } catch {
    // ignore: see load()
  }
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * The person resolving exceptions. dashboard-api v1 has no login, so this name is only an audit label
 * that is sent as resolvedBy. It is remembered in this browser.
 */
export function useOperator(): [string, (name: string) => void] {
  const name = useSyncExternalStore(subscribe, () => current);
  return [name, setOperator];
}
