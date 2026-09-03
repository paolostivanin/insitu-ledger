import { describe, it, expect } from 'vitest';
import { rollUpByParent } from './categoryRollup';
import type { CategoryReport } from './api/client';

function row(p: Partial<CategoryReport> & { category_id: number; total: number }): CategoryReport {
	return {
		category_name: `cat-${p.category_id}`,
		category_color: null,
		parent_id: null,
		parent_name: null,
		parent_color: null,
		type: 'expense',
		...p
	};
}

describe('rollUpByParent', () => {
	it('sums children plus the parent\'s own transactions into one bucket', () => {
		const out = rollUpByParent([
			row({ category_id: 1, category_name: 'Food', category_color: '#f00', total: 40 }),
			row({ category_id: 2, category_name: 'Groceries', parent_id: 1, parent_name: 'Food', parent_color: '#f00', total: 100 }),
			row({ category_id: 3, category_name: 'Dining', parent_id: 1, parent_name: 'Food', parent_color: '#f00', total: 60 })
		]);

		expect(out).toHaveLength(1);
		expect(out[0]).toMatchObject({
			category_id: 1,
			category_name: 'Food',
			category_color: '#f00',
			total: 200,
			parent_id: null
		});
	});

	it('takes the parent identity even when the parent row arrives last', () => {
		const out = rollUpByParent([
			row({ category_id: 2, category_name: 'Groceries', parent_id: 1, parent_name: 'Food', parent_color: '#f00', total: 100 }),
			row({ category_id: 1, category_name: 'Food', category_color: '#f00', total: 40 })
		]);

		expect(out).toHaveLength(1);
		expect(out[0].category_name).toBe('Food');
		expect(out[0].category_color).toBe('#f00');
		expect(out[0].total).toBe(140);
	});

	it('leaves top-level categories untouched', () => {
		const out = rollUpByParent([row({ category_id: 9, category_name: 'Rent', total: 700 })]);
		expect(out).toHaveLength(1);
		expect(out[0]).toMatchObject({ category_id: 9, category_name: 'Rent', total: 700 });
	});

	it('keeps a child of a soft-deleted parent as its own bucket', () => {
		// The backend nulls parent_id when the parent is soft-deleted, so the
		// orphan must never collapse into a nameless bucket.
		const out = rollUpByParent([
			row({ category_id: 5, category_name: 'Orphan', total: 30 }),
			row({ category_id: 6, category_name: 'Rent', total: 70 })
		]);
		expect(out.map((r) => r.category_name)).toEqual(['Rent', 'Orphan']);
	});

	it('never merges income and expense children under one parent', () => {
		const out = rollUpByParent([
			row({ category_id: 2, parent_id: 1, parent_name: 'Side gig', type: 'expense', total: 30 }),
			row({ category_id: 3, parent_id: 1, parent_name: 'Side gig', type: 'income', total: 500 })
		]);

		expect(out).toHaveLength(2);
		expect(out.find((r) => r.type === 'income')?.total).toBe(500);
		expect(out.find((r) => r.type === 'expense')?.total).toBe(30);
	});

	it('falls back to the child colour when the parent has none', () => {
		const out = rollUpByParent([
			row({ category_id: 2, category_color: '#0f0', parent_id: 1, parent_name: 'Food', parent_color: null, total: 10 })
		]);
		expect(out[0].category_color).toBe('#0f0');
	});

	it('re-sorts buckets by total descending', () => {
		const out = rollUpByParent([
			row({ category_id: 1, category_name: 'Small', total: 10 }),
			row({ category_id: 3, category_name: 'Child', parent_id: 2, parent_name: 'Big', total: 5 }),
			row({ category_id: 4, category_name: 'Child2', parent_id: 2, parent_name: 'Big', total: 90 })
		]);
		expect(out.map((r) => r.category_name)).toEqual(['Big', 'Small']);
		expect(out[0].total).toBe(95);
	});

	it('does not mutate the input rows', () => {
		const input = [
			row({ category_id: 2, parent_id: 1, parent_name: 'Food', total: 100 }),
			row({ category_id: 3, parent_id: 1, parent_name: 'Food', total: 60 })
		];
		rollUpByParent(input);
		expect(input[0].total).toBe(100);
		expect(input[0].category_id).toBe(2);
	});

	it('returns an empty array for no rows', () => {
		expect(rollUpByParent([])).toEqual([]);
	});
});
