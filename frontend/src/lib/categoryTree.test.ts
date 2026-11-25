import { describe, expect, it } from 'vitest';
import { categoryForType, formCategoryError, keepFormCategory } from './categoryTree';
import type { Category } from './api/client';

const cat = (id: number, type: Category['type'], name: string, parent_id: number | null = null) =>
	({ id, user_id: 1, parent_id, name, type }) as Category;

// API order is by name, so an income category can come first.
const cats = [cat(1, 'income', 'Aaa Salary'), cat(2, 'expense', 'Food'), cat(3, 'expense', 'Groceries', 2), cat(4, 'income', 'Bonus')];

describe('categoryForType', () => {
	it('keeps the current category when it already has the requested type', () => {
		expect(categoryForType(cats, 'expense', 3)).toBe(3);
		expect(categoryForType(cats, 'income', 4)).toBe(4);
	});

	it('replaces a category of the other type with the first one of the requested type', () => {
		expect(categoryForType(cats, 'expense', 1)).toBe(2);
		expect(categoryForType(cats, 'income', 2)).toBe(1);
	});

	it('never picks the first category regardless of type', () => {
		expect(categoryForType(cats, 'expense', 0)).toBe(2);
	});

	it('replaces an unknown id and returns 0 when no category of the type exists', () => {
		expect(categoryForType(cats, 'expense', 999)).toBe(2);
		expect(categoryForType([cat(1, 'income', 'Salary')], 'expense', 1)).toBe(0);
		expect(categoryForType([], 'income', 0)).toBe(0);
	});
});

describe('formCategoryError', () => {
	const expense = 'Choose an expense category';
	const income = 'Choose an income category';

	it('accepts a listed category of the entry type and refuses one of the other type', () => {
		expect(formCategoryError(cats, 'expense', 2, null)).toBeNull();
		expect(formCategoryError(cats, 'expense', 1, null)).toBe(expense);
		expect(formCategoryError(cats, 'income', 3, null)).toBe(income);
	});

	it("refuses a listed category of the other type even when it is the entry's own", () => {
		expect(formCategoryError(cats, 'expense', 1, { categoryId: 1, type: 'expense' })).toBe(expense);
	});

	it("accepts the edited entry's own unlisted (soft-deleted) category while the type is unchanged", () => {
		expect(formCategoryError(cats, 'expense', 99, { categoryId: 99, type: 'expense' })).toBeNull();
		expect(formCategoryError([], 'income', 99, { categoryId: 99, type: 'income' })).toBeNull();
	});

	it('refuses an unlisted category once the type changed', () => {
		expect(formCategoryError(cats, 'income', 99, { categoryId: 99, type: 'expense' })).toBe(income);
	});

	it("refuses an unlisted category that is not the entry's own, and any unlisted one on create", () => {
		expect(formCategoryError(cats, 'expense', 98, { categoryId: 99, type: 'expense' })).toBe(expense);
		expect(formCategoryError(cats, 'expense', 99, null)).toBe(expense);
		expect(formCategoryError(cats, 'expense', 0, null)).toBe(expense);
		expect(formCategoryError([], 'expense', 0, { categoryId: 99, type: 'expense' })).toBe(expense);
	});
});

describe('keepFormCategory', () => {
	it("keeps a listed category, or the edited entry's own unlisted one", () => {
		expect(keepFormCategory(cats, 2, null)).toBe(true);
		expect(keepFormCategory(cats, 99, { categoryId: 99, type: 'expense' })).toBe(true);
		expect(keepFormCategory(cats, 99, null)).toBe(false);
		expect(keepFormCategory(cats, 98, { categoryId: 99, type: 'expense' })).toBe(false);
	});
});
