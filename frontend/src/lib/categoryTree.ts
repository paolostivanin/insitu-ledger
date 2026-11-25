import type { Category } from './api/client';

export interface CategoryNode {
	cat: Category;
	// 0 = top level, 1 = child, 2 = grandchild, …
	depth: number;
}

/**
 * Categories in picker order: each parent followed by its children, keeping
 * the input order (the API sorts by name) within each level. Pass `type` to
 * keep only that type.
 *
 * Every category is listed exactly once. A child whose parent is missing from
 * the list (soft-deleted parent, parent of the other type, a parent_id cycle)
 * is shown at the top level instead of disappearing, so a picker can always
 * display the category it holds.
 */
export function categoryTree(cats: readonly Category[], type?: Category['type']): CategoryNode[] {
	const list = type ? cats.filter(c => c.type === type) : [...cats];
	const ids = new Set(list.map(c => c.id));
	const children = new Map<number, Category[]>();
	const roots: Category[] = [];
	for (const c of list) {
		if (c.parent_id != null && c.parent_id !== c.id && ids.has(c.parent_id)) {
			const siblings = children.get(c.parent_id);
			if (siblings) siblings.push(c);
			else children.set(c.parent_id, [c]);
		} else {
			roots.push(c);
		}
	}

	const out: CategoryNode[] = [];
	const seen = new Set<number>();
	const walk = (c: Category, depth: number) => {
		if (seen.has(c.id)) return;
		seen.add(c.id);
		out.push({ cat: c, depth });
		for (const child of children.get(c.id) ?? []) walk(child, depth + 1);
	};
	for (const root of roots) walk(root, 0);
	// Members of a parent_id cycle are never reachable from a root.
	for (const c of list) walk(c, 0);
	return out;
}

/**
 * The category a form should hold for `type`: `current` when it is a category
 * of that type, otherwise the first one the picker lists (0 when there is
 * none). Keeps a form from submitting a category of the other type.
 */
export function categoryForType(cats: readonly Category[], type: Category['type'], current: number): number {
	if (cats.some(c => c.id === current && c.type === type)) return current;
	return categoryTree(cats, type)[0]?.cat.id ?? 0;
}

/** The category and type an edited entry had when its form opened. */
export interface OriginalCategory {
	categoryId: number;
	type: Category['type'];
}

/**
 * Why a single-entry form may not be saved with this category, or null when
 * it may. A listed category must have the entry's type. A category missing
 * from `cats` (soft-deleted, so the API no longer lists it) is accepted only
 * when it is the edited entry's own, kept with its type unchanged, so an
 * amount-only edit still saves. Anything else unknown (0, another id) is
 * refused.
 */
export function formCategoryError(
	cats: readonly Category[],
	type: Category['type'],
	categoryId: number,
	original: OriginalCategory | null
): string | null {
	const message = `Choose ${type === 'income' ? 'an income' : 'an expense'} category`;
	const listed = cats.find(c => c.id === categoryId);
	if (listed) return listed.type === type ? null : message;
	const keepsOriginal = original !== null && categoryId === original.categoryId && type === original.type;
	return keepsOriginal ? null : message;
}

/**
 * Whether a reference-data reload should leave the form's category alone:
 * a category that is still listed, or the edited entry's own unlisted one.
 */
export function keepFormCategory(cats: readonly Category[], categoryId: number, original: OriginalCategory | null): boolean {
	return cats.some(c => c.id === categoryId) || (original !== null && categoryId === original.categoryId);
}
