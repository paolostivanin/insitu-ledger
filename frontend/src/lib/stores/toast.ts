import { writable } from 'svelte/store';

export interface Toast {
	id: number;
	message: string;
	type: 'success' | 'error';
}

let nextId = 0;
export const toasts = writable<Toast[]>([]);

export function addToast(message: string, type: 'success' | 'error' = 'success', duration = 4000) {
	const id = nextId++;
	toasts.update(t => [...t, { id, message, type }]);
	setTimeout(() => {
		toasts.update(t => t.filter(x => x.id !== id));
	}, duration);
}

export function dismissToast(id: number) {
	toasts.update(t => t.filter(x => x.id !== id));
}
