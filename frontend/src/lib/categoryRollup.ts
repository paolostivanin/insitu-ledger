import type { CategoryReport } from '$lib/api/client';

/**
 * Collapse child-category rows into their parent so a report can answer
 * "how much did I spend on Food?" — Food's own transactions plus every
 * subcategory's.
 *
 * `type` stays part of the bucket key: nothing stops a user from parenting an
 * income category under an expense one, and summing those into a single figure
 * would be nonsense.
 *
 * Rows already carry the live parent (the backend nulls `parent_id` when the
 * parent is soft-deleted), so a child of a deleted parent stays its own row.
 * Output keeps the CategoryReport shape, so callers render it unchanged.
 */
export function rollUpByParent(rows: CategoryReport[]): CategoryReport[] {
	const buckets = new Map<string, CategoryReport>();

	for (const row of rows) {
		const id = row.parent_id ?? row.category_id;
		const key = `${id}:${row.type}`;
		const existing = buckets.get(key);

		if (existing) {
			existing.total += row.total;
			// A parent row can arrive after its children; take its identity as
			// soon as we see it, since the child only knows the parent's name
			// via parent_name (which may be a colourless placeholder).
			if (row.parent_id === null) {
				existing.category_name = row.category_name;
				existing.category_color = row.category_color ?? existing.category_color;
			}
			continue;
		}

		buckets.set(key, {
			...row,
			category_id: id,
			category_name: row.parent_id === null ? row.category_name : (row.parent_name ?? row.category_name),
			// Fall back to the child's colour so a parent without one still
			// gets a stable slice colour instead of an ECharts default.
			category_color: row.parent_id === null ? row.category_color : (row.parent_color ?? row.category_color),
			parent_id: null,
			parent_name: null,
			parent_color: null
		});
	}

	return [...buckets.values()].sort((a, b) => b.total - a.total);
}
