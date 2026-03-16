import { describe, it, expect, beforeEach } from 'vitest';
import { get } from 'svelte/store';
import { theme, toggleTheme, initTheme } from './theme';

describe('theme store', () => {
	beforeEach(() => {
		localStorage.clear();
		document.documentElement.removeAttribute('data-theme');
	});

	it('defaults to dark', () => {
		initTheme();
		expect(get(theme)).toBe('dark');
	});

	it('toggles from dark to light', () => {
		initTheme();
		toggleTheme();
		expect(get(theme)).toBe('light');
		expect(localStorage.getItem('theme')).toBe('light');
		expect(document.documentElement.getAttribute('data-theme')).toBe('light');
	});

	it('toggles back to dark', () => {
		initTheme();
		toggleTheme();
		toggleTheme();
		expect(get(theme)).toBe('dark');
	});

	it('reads from localStorage', () => {
		localStorage.setItem('theme', 'light');
		initTheme();
		expect(get(theme)).toBe('light');
	});
});
