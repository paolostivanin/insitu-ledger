import { describe, it, expect, vi } from 'vitest';
import { get } from 'svelte/store';
import { toasts, addToast, dismissToast } from './toast';

describe('toast store', () => {
	it('adds a toast', () => {
		addToast('Hello', 'success', 100000);
		const t = get(toasts);
		expect(t.length).toBeGreaterThanOrEqual(1);
		const last = t[t.length - 1];
		expect(last.message).toBe('Hello');
		expect(last.type).toBe('success');
	});

	it('dismisses a toast', () => {
		addToast('To dismiss', 'error', 100000);
		const before = get(toasts);
		const id = before[before.length - 1].id;
		dismissToast(id);
		const after = get(toasts);
		expect(after.find(t => t.id === id)).toBeUndefined();
	});

	it('auto-dismisses after duration', async () => {
		vi.useFakeTimers();
		addToast('Auto', 'success', 100);
		const before = get(toasts);
		const id = before[before.length - 1].id;
		vi.advanceTimersByTime(150);
		const after = get(toasts);
		expect(after.find(t => t.id === id)).toBeUndefined();
		vi.useRealTimers();
	});
});
