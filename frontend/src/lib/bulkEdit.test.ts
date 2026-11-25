import { describe, expect, it, vi } from 'vitest';
import type { Category, ScheduledTransaction, Transaction } from './api/client';
import {
	BULK_CAP,
	MIXED_OWNERS_REASON,
	MIXED_TYPES_REASON,
	NO_CATEGORIES_REASON,
	SCHEDULE_OWNER_CONFLICT,
	SCHEDULE_TYPE_CONFLICT,
	buildBulkPayload,
	bulkResultMessage,
	categoryDisabledReason,
	categoryOptions,
	fetchAllMatching,
	initialCategoryId,
	missingScheduleIds,
	nameError,
	scheduleConflict,
	summarizeSelection,
	utf8Length,
	type BulkForm
} from './bulkEdit';
import { categoryTree } from './categoryTree';

function tx(id: number, over: Partial<Transaction> = {}): Transaction {
	return {
		id, account_id: 1, category_id: 10, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
		description: 'Stipendio', note: null, date: '2026-01-01', created_at: '', updated_at: '',
		sync_version: 1, created_by_user_id: 1, created_by_name: 'Owner', ...over
	};
}

function cat(id: number, over: Partial<Category> = {}): Category {
	return {
		id, user_id: 1, parent_id: null, name: `Cat ${id}`, type: 'expense', icon: null, color: null,
		created_at: '', updated_at: '', sync_version: 1, ...over
	};
}

function sched(id: number, over: Partial<ScheduledTransaction> = {}): ScheduledTransaction {
	return {
		id, account_id: 1, category_id: 10, user_id: 1, type: 'expense', amount: 5, currency: 'EUR',
		description: 'Stipendio', note: null, rrule: 'FREQ=MONTHLY', next_occurrence: '2026-02-01',
		active: true, max_occurrences: null, occurrence_count: 1, created_at: '', updated_at: '',
		deleted_at: null, sync_version: 1, created_by_user_id: 1, created_by_name: 'Owner', ...over
	};
}

const schedules = (...list: ScheduledTransaction[]) => new Map(list.map(s => [s.id, s]));
const noSchedules = new Map<number, ScheduledTransaction>();

// Owner 1: expense Food(10) > Groceries(11), expense Rent(12), income Salary(20);
// owner 2: expense Other(30); a deleted owner-1 expense category (13).
const cats: Category[] = [
	cat(10, { name: 'Food' }),
	cat(11, { name: 'Groceries', parent_id: 10 }),
	cat(12, { name: 'Rent' }),
	cat(13, { name: 'Gone', deleted_at: '2026-01-01' } as Partial<Category>),
	cat(20, { name: 'Salary', type: 'income' }),
	cat(30, { name: 'Other', user_id: 2 })
];

const form = (over: Partial<BulkForm> = {}): BulkForm => ({
	changeName: false, name: '', changeCategory: false, categoryId: 0, updateSchedules: false, ...over
});

describe('utf8Length', () => {
	it('counts UTF-8 bytes, not characters', () => {
		expect(utf8Length('')).toBe(0);
		expect(utf8Length('abc')).toBe(3);
		expect(utf8Length('è')).toBe(2);
		expect(utf8Length('€')).toBe(3);
		expect(utf8Length('😀')).toBe(4);
		expect(utf8Length('Stipendió')).toBe(10);
	});
});

describe('nameError', () => {
	it.each(['', '   ', '\t\n '])('rejects a blank name %j', name => {
		expect(nameError(name)).toBe('Enter a name.');
	});

	it('accepts exactly 500 bytes and rejects 501, for ASCII and for two-byte letters', () => {
		expect(nameError('a'.repeat(500))).toBeNull();
		expect(nameError('a'.repeat(501))).toContain('501/500');
		expect(nameError('è'.repeat(250))).toBeNull();
		expect(nameError('è'.repeat(250) + 'a')).toContain('501/500');
		// 251 characters but 502 bytes: a character count would wrongly accept it.
		expect(nameError('è'.repeat(251))).toContain('502/500');
	});

	it('ignores surrounding whitespace when measuring', () => {
		expect(nameError(`   ${'a'.repeat(500)}   `)).toBeNull();
	});
});

describe('summarizeSelection', () => {
	it('reports the single type, owner, category and description', () => {
		const s = summarizeSelection([tx(1), tx(2)], noSchedules);
		expect(s).toMatchObject({
			count: 2, types: ['expense'], owners: [1], type: 'expense', owner: 1,
			commonCategoryId: 10, commonDescription: 'Stipendio', scheduleIds: [], categoryBlockedReason: null
		});
	});

	it('counts a duplicated row once', () => {
		expect(summarizeSelection([tx(1), tx(1), tx(2)], noSchedules).count).toBe(2);
	});

	it('has no common category or description when they differ', () => {
		const s = summarizeSelection([tx(1), tx(2, { category_id: 12, description: 'Stipendio Lisa' })], noSchedules);
		expect(s.commonCategoryId).toBeNull();
		expect(s.commonDescription).toBeNull();
	});

	it('blocks the category for mixed types, and for mixed owners', () => {
		const types = summarizeSelection([tx(1), tx(2, { type: 'income' })], noSchedules);
		expect(types).toMatchObject({ type: null, types: ['expense', 'income'], categoryBlockedReason: MIXED_TYPES_REASON });
		const owners = summarizeSelection([tx(1), tx(2, { user_id: 2 })], noSchedules);
		expect(owners).toMatchObject({ owner: null, owners: [1, 2], categoryBlockedReason: MIXED_OWNERS_REASON });
	});

	it('links only schedules that are present and not soft-deleted; paused ones count', () => {
		const rows = [
			tx(1, { scheduled_id: 5 }), tx(2, { scheduled_id: 5 }), // same live schedule, counted once
			tx(3, { scheduled_id: 6 }), // finished (soft-deleted)
			tx(4, { scheduled_id: 7 }), // not loaded / inaccessible
			tx(5, { scheduled_id: 8 }), // paused
			tx(6, { scheduled_id: 9 }), // older server without deleted_at
			tx(7)
		];
		const legacy = sched(9);
		delete legacy.deleted_at;
		const s = summarizeSelection(rows, schedules(
			sched(5), sched(6, { deleted_at: '2026-03-01T00:00:00Z' }), sched(8, { active: false }), legacy
		));
		expect(s.scheduleIds).toEqual([5, 8, 9]);
	});

	it('collects the types and owners of linked schedules', () => {
		const s = summarizeSelection(
			[tx(1, { scheduled_id: 5 }), tx(2, { scheduled_id: 6 })],
			schedules(sched(5), sched(6, { type: 'income', user_id: 2 }))
		);
		expect(s.scheduleTypes).toEqual(['expense', 'income']);
		expect(s.scheduleOwners).toEqual([1, 2]);
	});

	it('describes an empty selection without blocking reasons', () => {
		expect(summarizeSelection([], noSchedules)).toMatchObject({ count: 0, type: null, owner: null, categoryBlockedReason: null });
	});
});

describe('missingScheduleIds', () => {
	it('lists linked schedules absent from the map, once each; soft-deleted ones are present', () => {
		const rows = [
			tx(1, { scheduled_id: 7 }), tx(2, { scheduled_id: 7 }), tx(3, { scheduled_id: 4 }),
			tx(4, { scheduled_id: 5 }), tx(5, { scheduled_id: 6 }), tx(6), tx(7, { scheduled_id: null })
		];
		const loaded = schedules(sched(5), sched(6, { deleted_at: '2026-03-01T00:00:00Z' }));
		expect(missingScheduleIds(rows, loaded)).toEqual([4, 7]);
		expect(missingScheduleIds(rows, schedules(sched(4), sched(5), sched(6), sched(7)))).toEqual([]);
		expect(missingScheduleIds([tx(1)], noSchedules)).toEqual([]);
	});
});

describe('categoryOptions', () => {
	it("lists the owner's live categories of the selection's type, in picker order", () => {
		expect(categoryOptions(cats, 1, 'expense').map(c => c.id)).toEqual([10, 11, 12]);
		expect(categoryOptions(cats, 1, 'income').map(c => c.id)).toEqual([20]);
		expect(categoryOptions(cats, 2, 'expense').map(c => c.id)).toEqual([30]);
	});

	it('is empty when the owner or type is not a single value', () => {
		expect(categoryOptions(cats, null, 'expense')).toEqual([]);
		expect(categoryOptions(cats, 1, null)).toEqual([]);
	});

	it('keeps a child whose parent is gone, at the top level', () => {
		const orphan = cat(14, { name: 'Orphan', parent_id: 99 });
		expect(categoryOptions([...cats, orphan], 1, 'expense').map(c => c.id)).toEqual([10, 11, 12, 14]);
	});
});

describe('categoryTree', () => {
	it('lists every category exactly once, even with a parent_id cycle', () => {
		const list = [cat(1, { parent_id: 2 }), cat(2, { parent_id: 1 }), cat(3), cat(4, { parent_id: 3 }), cat(5, { parent_id: 4 })];
		const tree = categoryTree(list);
		expect(tree.map(n => n.cat.id).sort()).toEqual([1, 2, 3, 4, 5]);
		expect(tree.slice(0, 3).map(n => [n.cat.id, n.depth])).toEqual([[3, 0], [4, 1], [5, 2]]);
	});
});

describe('categoryDisabledReason and initialCategoryId', () => {
	it('explains why the category cannot change', () => {
		expect(categoryDisabledReason(summarizeSelection([tx(1), tx(2, { type: 'income' })], noSchedules), cats)).toBe(MIXED_TYPES_REASON);
		expect(categoryDisabledReason(summarizeSelection([tx(1, { user_id: 3 })], noSchedules), cats)).toBe(NO_CATEGORIES_REASON);
		expect(categoryDisabledReason(summarizeSelection([tx(1)], noSchedules), cats)).toBeNull();
	});

	it('starts from the shared category when it is allowed, else the first option shown', () => {
		expect(initialCategoryId(summarizeSelection([tx(1, { category_id: 12 })], noSchedules), cats)).toBe(12);
		expect(initialCategoryId(summarizeSelection([tx(1), tx(2, { category_id: 12 })], noSchedules), cats)).toBe(10);
		// A shared category that is not an option (deleted) never becomes the initial value.
		expect(initialCategoryId(summarizeSelection([tx(1, { category_id: 13 })], noSchedules), cats)).toBe(10);
		expect(initialCategoryId(summarizeSelection([tx(1, { user_id: 3 })], noSchedules), cats)).toBe(0);
	});
});

describe('buildBulkPayload', () => {
	const rows = [tx(3), tx(1), tx(2)];
	const summary = summarizeSelection(rows, noSchedules);
	const ids = rows.map(r => r.id);

	it('sends nothing when no field is opted into', () => {
		expect(buildBulkPayload(ids, form({ name: 'Salary', categoryId: 12, updateSchedules: true }), summary, cats)).toBeNull();
	});

	it('sends only the trimmed name when only the name is ticked', () => {
		const payload = buildBulkPayload(ids, form({ changeName: true, name: '  Salary  ', categoryId: 12 }), summary, cats);
		expect(payload).toStrictEqual({ ids: [1, 2, 3], description: 'Salary' });
	});

	it('sends only the category when only the category is ticked', () => {
		const payload = buildBulkPayload(ids, form({ changeCategory: true, categoryId: 12, name: 'ignored' }), summary, cats);
		expect(payload).toStrictEqual({ ids: [1, 2, 3], category_id: 12 });
	});

	it('sends both when both are ticked', () => {
		const payload = buildBulkPayload(ids, form({ changeName: true, name: 'Salary', changeCategory: true, categoryId: 11 }), summary, cats);
		expect(payload).toStrictEqual({ ids: [1, 2, 3], description: 'Salary', category_id: 11 });
	});

	it('refuses a blank or over-long name', () => {
		for (const name of ['', '   ', 'è'.repeat(251)]) {
			expect(buildBulkPayload(ids, form({ changeName: true, name, changeCategory: true, categoryId: 12 }), summary, cats)).toBeNull();
		}
		expect(buildBulkPayload(ids, form({ changeName: true, name: 'è'.repeat(250) }), summary, cats)?.description).toBe('è'.repeat(250));
	});

	it.each([
		['another owner', 30], ['the other type', 20], ['a deleted category', 13], ['an unknown id', 999], ['no category', 0]
	])('refuses a category of %s', (_label, categoryId) => {
		expect(buildBulkPayload(ids, form({ changeName: true, name: 'Salary', changeCategory: true, categoryId }), summary, cats)).toBeNull();
	});

	it.each([
		['mixed types', [tx(1), tx(2, { type: 'income' })]],
		['mixed owners', [tx(1), tx(2, { user_id: 2 })]],
		['no categories for the owner', [tx(1, { user_id: 3 })]]
	])('never sends a category while the change is unavailable (%s)', (_label, mixed) => {
		const s = summarizeSelection(mixed, noSchedules);
		const mixedIds = mixed.map(r => r.id);
		expect(buildBulkPayload(mixedIds, form({ changeCategory: true, categoryId: 12 }), s, cats)).toBeNull();
		const payload = buildBulkPayload(mixedIds, form({ changeName: true, name: 'Salary', changeCategory: true, categoryId: 12 }), s, cats);
		expect(payload).toStrictEqual({ ids: mixedIds, description: 'Salary' });
	});

	describe('linked schedules', () => {
		const linked = [tx(1, { scheduled_id: 5 }), tx(2)];
		const linkedIds = linked.map(r => r.id);

		it('sends update_schedules only when ticked and a live schedule is linked', () => {
			const live = summarizeSelection(linked, schedules(sched(5)));
			const finished = summarizeSelection(linked, schedules(sched(5, { deleted_at: '2026-03-01' })));
			const rename = { changeName: true, name: 'Salary' };
			expect(buildBulkPayload(linkedIds, form(rename), live, cats)).toStrictEqual({ ids: [1, 2], description: 'Salary' });
			expect(buildBulkPayload(linkedIds, form({ ...rename, updateSchedules: true }), live, cats))
				.toStrictEqual({ ids: [1, 2], description: 'Salary', update_schedules: true });
			expect(buildBulkPayload(linkedIds, form({ ...rename, updateSchedules: true }), finished, cats))
				.toStrictEqual({ ids: [1, 2], description: 'Salary' });
		});

		it('blocks a category change that a linked schedule of another type would reject', () => {
			const s = summarizeSelection(linked, schedules(sched(5, { type: 'income' })));
			expect(buildBulkPayload(linkedIds, form({ changeCategory: true, categoryId: 12, updateSchedules: true }), s, cats)).toBeNull();
			// Unticked, the entries alone can change.
			expect(buildBulkPayload(linkedIds, form({ changeCategory: true, categoryId: 12 }), s, cats))
				.toStrictEqual({ ids: [1, 2], category_id: 12 });
			// A rename has no type to clash with.
			expect(buildBulkPayload(linkedIds, form({ changeName: true, name: 'Salary', updateSchedules: true }), s, cats))
				.toStrictEqual({ ids: [1, 2], description: 'Salary', update_schedules: true });
		});

		it('blocks a category change when a linked schedule belongs to another owner', () => {
			const s = summarizeSelection(linked, schedules(sched(5, { user_id: 2 })));
			expect(buildBulkPayload(linkedIds, form({ changeCategory: true, categoryId: 12, updateSchedules: true }), s, cats)).toBeNull();
		});
	});

	describe('ids', () => {
		it('deduplicates and sorts', () => {
			const s = summarizeSelection([tx(9), tx(4)], noSchedules);
			expect(buildBulkPayload([9, 4, 9], form({ changeName: true, name: 'x' }), s, cats)?.ids).toEqual([4, 9]);
		});

		it('refuses an empty selection, non-positive ids, or ids that disagree with the summary', () => {
			const rename = form({ changeName: true, name: 'x' });
			expect(buildBulkPayload([], rename, summarizeSelection([], noSchedules), cats)).toBeNull();
			expect(buildBulkPayload([0], rename, summarizeSelection([tx(0)], noSchedules), cats)).toBeNull();
			expect(buildBulkPayload([-1], rename, summarizeSelection([tx(-1)], noSchedules), cats)).toBeNull();
			expect(buildBulkPayload([1, 2], rename, summarizeSelection([tx(1)], noSchedules), cats)).toBeNull();
		});

		it(`accepts exactly ${BULK_CAP} ids and refuses one more`, () => {
			const rename = form({ changeName: true, name: 'x' });
			const atCap = Array.from({ length: BULK_CAP }, (_, i) => tx(i + 1));
			expect(buildBulkPayload(atCap.map(r => r.id), rename, summarizeSelection(atCap, noSchedules), cats)?.ids).toHaveLength(BULK_CAP);
			const over = [...atCap, tx(BULK_CAP + 1)];
			expect(buildBulkPayload(over.map(r => r.id), rename, summarizeSelection(over, noSchedules), cats)).toBeNull();
		});
	});
});

describe('scheduleConflict', () => {
	it('reports owner before type, and nothing without a category or schedules', () => {
		const both = summarizeSelection([tx(1, { scheduled_id: 5 })], schedules(sched(5, { user_id: 2, type: 'income' })));
		expect(scheduleConflict(both, cats[2])).toBe(SCHEDULE_OWNER_CONFLICT);
		const type = summarizeSelection([tx(1, { scheduled_id: 5 })], schedules(sched(5, { type: 'income' })));
		expect(scheduleConflict(type, cats[2])).toBe(SCHEDULE_TYPE_CONFLICT);
		expect(scheduleConflict(type, null)).toBeNull();
		expect(scheduleConflict(summarizeSelection([tx(1)], noSchedules), cats[2])).toBeNull();
	});
});

describe('bulkResultMessage', () => {
	const zero = { updated: 0, unchanged: 0, skipped: 0, schedules_updated: 0, schedules_unchanged: 0, schedules_skipped: 0 };

	it('reports entries, and schedules only when any were involved', () => {
		expect(bulkResultMessage({ ...zero, updated: 1 })).toBe('Updated 1 entry');
		expect(bulkResultMessage({ ...zero, updated: 3, unchanged: 2, skipped: 1 }))
			.toBe('Updated 3 entries, 2 already up to date, 1 skipped (deleted or missing)');
		expect(bulkResultMessage({ ...zero, updated: 2, schedules_updated: 1, schedules_skipped: 1 }))
			.toBe('Updated 2 entries · 1 recurring schedule updated, 1 skipped');
	});
});

describe('fetchAllMatching', () => {
	const ids = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => ({ id: from + i }));
	// A server holding `total` matching rows with ids 1..total.
	const server = (total: number) => vi.fn(async (offset: number, limit: number) => ids(offset + 1, Math.min(offset + limit, total)));
	const fresh = () => false;

	it('stops after a short page', async () => {
		const fetchPage = server(7);
		expect(await fetchAllMatching(fetchPage, { pageSize: 10, cap: 50, isStale: fresh })).toEqual({ kind: 'rows', rows: ids(1, 7) });
		expect(fetchPage.mock.calls).toEqual([[0, 10]]);
	});

	it('pages with the page size as limit and advancing offsets', async () => {
		const fetchPage = server(1120);
		const result = await fetchAllMatching(fetchPage, { isStale: fresh });
		expect(fetchPage.mock.calls).toEqual([[0, 500], [500, 500], [1000, 500]]);
		expect(result.kind === 'rows' && result.rows.map(r => r.id)).toEqual(ids(1, 1120).map(r => r.id));
	});

	it(`accepts exactly ${BULK_CAP} rows and refuses ${BULK_CAP + 1}`, async () => {
		const exact = server(BULK_CAP);
		const result = await fetchAllMatching(exact, { isStale: fresh });
		expect(result.kind === 'rows' && result.rows.length).toBe(BULK_CAP);
		expect(exact).toHaveBeenCalledTimes(11);
		expect(await fetchAllMatching(server(BULK_CAP + 1), { isStale: fresh })).toEqual({ kind: 'tooMany' });
	});

	it('deduplicates rows repeated across pages', async () => {
		const pages = [ids(1, 3), ids(3, 5), [{ id: 5 }]];
		const fetchPage = vi.fn(async () => pages.shift() ?? []);
		const result = await fetchAllMatching(fetchPage, { pageSize: 3, cap: 10, isStale: fresh });
		expect(result).toEqual({ kind: 'rows', rows: ids(1, 5) });
	});

	it('measures the cap on distinct rows', async () => {
		// 8 rows returned, 6 distinct: within a cap of 6.
		const pages = [ids(1, 3), ids(3, 5), ids(5, 6)];
		const fetchPage = vi.fn(async () => pages.shift() ?? []);
		expect(await fetchAllMatching(fetchPage, { pageSize: 3, cap: 6, isStale: fresh })).toEqual({ kind: 'rows', rows: ids(1, 6) });
	});

	it('reports stale without further requests once isStale turns true', async () => {
		let stale = false;
		const fetchPage = vi.fn(async (offset: number) => { if (offset === 10) stale = true; return ids(offset + 1, offset + 10); });
		expect(await fetchAllMatching(fetchPage, { pageSize: 10, cap: 100, isStale: () => stale })).toEqual({ kind: 'stale' });
		expect(fetchPage).toHaveBeenCalledTimes(2);
		const never = vi.fn(async () => ids(1, 3));
		expect(await fetchAllMatching(never, { pageSize: 10, cap: 100, isStale: () => true })).toEqual({ kind: 'stale' });
		expect(never).not.toHaveBeenCalled();
	});

	it('terminates against a server that ignores the offset', async () => {
		const fetchPage = vi.fn(async () => ids(1, 10));
		expect(await fetchAllMatching(fetchPage, { pageSize: 10, cap: 25, isStale: fresh })).toEqual({ kind: 'tooMany' });
		expect(fetchPage).toHaveBeenCalledTimes(3);
	});

	it('propagates a failed page', async () => {
		const fetchPage = vi.fn().mockResolvedValueOnce(ids(1, 10)).mockRejectedValueOnce(new Error('offline'));
		await expect(fetchAllMatching(fetchPage, { pageSize: 10, cap: 100, isStale: fresh })).rejects.toThrow('offline');
	});
});
