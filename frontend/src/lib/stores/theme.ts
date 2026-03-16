import { writable } from 'svelte/store';

function getInitialTheme(): 'dark' | 'light' {
	if (typeof window === 'undefined') return 'dark';
	return (localStorage.getItem('theme') as 'dark' | 'light') || 'dark';
}

export const theme = writable<'dark' | 'light'>(getInitialTheme());

export function toggleTheme() {
	theme.update(t => {
		const next = t === 'dark' ? 'light' : 'dark';
		localStorage.setItem('theme', next);
		document.documentElement.setAttribute('data-theme', next);
		return next;
	});
}

export function initTheme() {
	const t = getInitialTheme();
	theme.set(t);
	document.documentElement.setAttribute('data-theme', t);
}
