import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import CategoryPicker from './CategoryPicker.svelte';
import type { Category } from '../api/client';

vi.mock('../api/client', () => ({ categories: { create: vi.fn(), list: vi.fn() } }));
afterEach(cleanup);

const cat = (id: number, name: string, over: Partial<Category> = {}) =>
	({ id, user_id: 1, parent_id: null, name, type: 'expense', icon: null, color: null, ...over }) as Category;
const cats = [cat(1, 'Food'), cat(2, 'Rent'), cat(3, 'Salary', { type: 'income' }), cat(4, 'Orphan', { parent_id: 99 })];

// The first select is the picker; quick create adds a parent select below it.
const select = () => screen.getAllByRole('combobox')[0] as HTMLSelectElement;
const shown = () => select().selectedOptions[0]?.textContent?.replace(/\s+/g, ' ').trim();

it.each([
	['0', 0],
	['a category of the other type', 3],
	['an unknown category', 42]
])('never shows a real category for %s', (_label, value) => {
	render(CategoryPicker, { cats, type: 'expense', value, onchange: vi.fn() });
	expect(shown()).toBe('Choose a category…');
	expect(select().selectedOptions[0].disabled).toBe(true);
});

it('shows the held category, including a child whose parent is gone', () => {
	render(CategoryPicker, { cats, type: 'expense', value: 4, onchange: vi.fn() });
	expect(shown()).toBe('Orphan');
	expect(screen.queryByText('Choose a category…')).toBeNull();
});

it('reverts to the held value after "+ New category..." or a change the parent ignores', async () => {
	const onchange = vi.fn();
	render(CategoryPicker, { cats, type: 'expense', value: 2, onchange });
	await fireEvent.change(select(), { target: { value: '__new__' } });
	expect(shown()).toBe('Rent');
	expect(onchange).not.toHaveBeenCalled();
	await fireEvent.change(select(), { target: { value: '1' } });
	expect(onchange).toHaveBeenCalledWith(1);
	// The parent kept value=2, so the picker must still read "Rent".
	expect(shown()).toBe('Rent');
});

it('omits quick create when allowCreate is false', () => {
	render(CategoryPicker, { cats, type: 'expense', value: 1, onchange: vi.fn(), allowCreate: false });
	expect([...select().options].map(o => o.value)).toEqual(['1', '2', '4']);
});

it('quick create offers only real roots of the type as parents', async () => {
	const withCycle = [
		...cats,
		cat(5, 'Groceries', { parent_id: 1 }),
		cat(6, 'Loop A', { parent_id: 7 }),
		cat(7, 'Loop B', { parent_id: 6 }),
		cat(8, 'Bonus', { type: 'income' })
	];
	render(CategoryPicker, { cats: withCycle, type: 'expense', value: 1, onchange: vi.fn() });
	await fireEvent.change(select(), { target: { value: '__new__' } });
	const parents = screen.getAllByRole('combobox')[1] as HTMLSelectElement;
	expect([...parents.options].map(o => o.textContent?.trim())).toEqual(['Top level', 'Sub of: Food', 'Sub of: Rent']);
});
