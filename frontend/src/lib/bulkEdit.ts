// Pure helpers behind the transactions page's bulk edit (rename / recategorize
// many entries at once). The server (POST /api/transactions/batch-update)
// re-checks everything; these mirror its rules so the dialog never offers a
// request it would reject, and never sends a field the user didn't opt into.

import type {
	BatchUpdateInput,
	BatchUpdateResult,
	Category,
	ScheduledTransaction,
	Transaction
} from './api/client';
import { categoryTree } from './categoryTree';

/** Most ids one request may carry; the server answers 400 above it. */
export const BULK_CAP = 5000;
/** The server's description limit, counted in UTF-8 bytes. */
export const NAME_MAX_BYTES = 500;
/** Page size for "select all matching" (the list endpoint's maximum). */
export const SELECT_ALL_PAGE_SIZE = 500;

type EntryType = Transaction['type'];

export const MIXED_TYPES_REASON =
	'The selection mixes income and expense entries. Select only one type to change the category.';
export const MIXED_OWNERS_REASON =
	'The selection includes entries from different owners. Change the category for one owner at a time.';
export const NO_CATEGORIES_REASON = 'There is no category of this type for the owner of these entries.';
export const SCHEDULE_OWNER_CONFLICT =
	'A linked recurring schedule belongs to a different owner. Untick the schedule option to change only the entries.';
export const SCHEDULE_TYPE_CONFLICT =
	'A linked recurring schedule has a different type than the new category. Untick the schedule option to change only the entries.';
export const SCHEDULES_UNAVAILABLE_REASON = "Some linked recurring schedules couldn't be loaded.";

export function utf8Length(s: string): number {
	return new TextEncoder().encode(s).length;
}

export interface SelectionSummary {
	/** Distinct selected ids. */
	count: number;
	/** Distinct entry types, sorted. */
	types: EntryType[];
	/** Distinct row owners (transactions.user_id = the account owner), ascending. */
	owners: number[];
	/** The single type, or null when the selection is mixed or empty. */
	type: EntryType | null;
	/** The single owner, or null when the selection spans owners or is empty. */
	owner: number | null;
	/** The category every selected entry shares, else null. */
	commonCategoryId: number | null;
	/** The description every selected entry shares, else null. */
	commonDescription: string | null;
	/** Live (present and not soft-deleted) schedules linked to the selection, ascending. */
	scheduleIds: number[];
	scheduleTypes: EntryType[];
	scheduleOwners: number[];
	/** Why the category can't change for this selection (mixed types or owners), else null. */
	categoryBlockedReason: string | null;
}

/**
 * Describe a selection. `schedules` must be loaded with include_deleted=1: a
 * schedule counts as linked only if it is present AND not soft-deleted
 * (finished series are soft-deleted; paused ones are live). A schedule that is
 * missing (not loaded, inaccessible) is never offered.
 */
export function summarizeSelection(
	rows: readonly Transaction[],
	schedules: ReadonlyMap<number, ScheduledTransaction>
): SelectionSummary {
	const byId = new Map<number, Transaction>();
	for (const row of rows) if (!byId.has(row.id)) byId.set(row.id, row);

	const types = new Set<EntryType>();
	const owners = new Set<number>();
	const categories = new Set<number>();
	const descriptions = new Set<string | null>();
	const live = new Map<number, ScheduledTransaction>();
	for (const row of byId.values()) {
		types.add(row.type);
		owners.add(row.user_id);
		categories.add(row.category_id);
		descriptions.add(row.description ?? null);
		if (row.scheduled_id != null) {
			const schedule = schedules.get(row.scheduled_id);
			if (schedule && schedule.deleted_at == null) live.set(row.scheduled_id, schedule);
		}
	}

	const typeList = [...types].sort();
	const ownerList = [...owners].sort((a, b) => a - b);
	const liveList = [...live.values()];
	return {
		count: byId.size,
		types: typeList,
		owners: ownerList,
		type: typeList.length === 1 ? typeList[0] : null,
		owner: ownerList.length === 1 ? ownerList[0] : null,
		commonCategoryId: categories.size === 1 ? [...categories][0] : null,
		commonDescription: descriptions.size === 1 ? [...descriptions][0] : null,
		scheduleIds: [...live.keys()].sort((a, b) => a - b),
		scheduleTypes: [...new Set(liveList.map(s => s.type))].sort(),
		scheduleOwners: [...new Set(liveList.map(s => s.user_id))].sort((a, b) => a - b),
		categoryBlockedReason:
			typeList.length > 1 ? MIXED_TYPES_REASON : ownerList.length > 1 ? MIXED_OWNERS_REASON : null
	};
}

/**
 * Linked schedule ids that are not in `schedules` at all (not loaded yet, or
 * the fetch failed), ascending. The server updates every live accessible
 * linked schedule, so while any is missing the client can't say how many
 * the schedule option would reach.
 */
export function missingScheduleIds(
	rows: Iterable<Transaction>,
	schedules: ReadonlyMap<number, ScheduledTransaction>
): number[] {
	const missing = new Set<number>();
	for (const row of rows) {
		if (row.scheduled_id != null && !schedules.has(row.scheduled_id)) missing.add(row.scheduled_id);
	}
	return [...missing].sort((a, b) => a - b);
}

/**
 * Categories the selection may move to: the row owner's live categories of
 * the selection's type, in picker display order (so [0] is the first option
 * the picker shows). Empty when owner or type is not a single value.
 */
export function categoryOptions(
	cats: readonly Category[],
	owner: number | null,
	type: EntryType | null
): Category[] {
	if (owner == null || type == null) return [];
	// The API already omits deleted categories; this guards against a client
	// that ever lists them.
	const usable = cats.filter(
		c => c.user_id === owner && (c as Category & { deleted_at?: string | null }).deleted_at == null
	);
	return categoryTree(usable, type).map(node => node.cat);
}

/** Why "Change category" is unavailable, or null when it can be used. */
export function categoryDisabledReason(summary: SelectionSummary, cats: readonly Category[]): string | null {
	if (summary.categoryBlockedReason) return summary.categoryBlockedReason;
	if (categoryOptions(cats, summary.owner, summary.type).length === 0) return NO_CATEGORIES_REASON;
	return null;
}

/** The category a fresh dialog shows: the shared one if allowed, else the first option. */
export function initialCategoryId(summary: SelectionSummary, cats: readonly Category[]): number {
	const options = categoryOptions(cats, summary.owner, summary.type);
	const common = options.find(c => c.id === summary.commonCategoryId);
	return (common ?? options[0])?.id ?? 0;
}

/** Validation message for a new name, or null when it is acceptable. */
export function nameError(name: string): string | null {
	const trimmed = name.trim();
	if (!trimmed) return 'Enter a name.';
	const bytes = utf8Length(trimmed);
	if (bytes > NAME_MAX_BYTES) return `The name is too long (${bytes}/${NAME_MAX_BYTES} bytes).`;
	return null;
}

/**
 * The server rejects a category change that would also move a linked schedule
 * to another owner's category or a category of another type; report it first.
 */
export function scheduleConflict(summary: SelectionSummary, category: Category | null | undefined): string | null {
	if (!category || summary.scheduleIds.length === 0) return null;
	if (summary.scheduleOwners.some(owner => owner !== category.user_id)) return SCHEDULE_OWNER_CONFLICT;
	if (summary.scheduleTypes.some(type => type !== category.type)) return SCHEDULE_TYPE_CONFLICT;
	return null;
}

export interface BulkForm {
	changeName: boolean;
	name: string;
	changeCategory: boolean;
	categoryId: number;
	updateSchedules: boolean;
}

/**
 * The request for a bulk edit, or null when there is nothing valid to send.
 * - Only opted-in fields are present; the name is sent trimmed.
 * - A category is never sent while the category change is unavailable (mixed
 *   selection, no options), and only if it is one of `categoryOptions`.
 * - update_schedules is present (true) only when ticked and the selection has
 *   live linked schedules; a schedule conflict blocks the whole request.
 * - ids are deduplicated and sorted, and must match the summarized selection.
 */
export function buildBulkPayload(
	ids: Iterable<number>,
	form: BulkForm,
	summary: SelectionSummary,
	cats: readonly Category[]
): BatchUpdateInput | null {
	const unique = [...new Set(ids)].sort((a, b) => a - b);
	if (unique.length === 0 || unique.length > BULK_CAP || unique.length !== summary.count) return null;
	if (unique.some(id => !Number.isInteger(id) || id <= 0)) return null;

	const payload: BatchUpdateInput = { ids: unique };
	if (form.changeName) {
		if (nameError(form.name)) return null;
		payload.description = form.name.trim();
	}

	let category: Category | undefined;
	if (form.changeCategory && categoryDisabledReason(summary, cats) == null) {
		category = categoryOptions(cats, summary.owner, summary.type).find(c => c.id === form.categoryId);
		if (!category) return null;
		payload.category_id = category.id;
	}

	if (payload.description === undefined && payload.category_id === undefined) return null;

	if (form.updateSchedules && summary.scheduleIds.length > 0) {
		if (scheduleConflict(summary, category)) return null;
		payload.update_schedules = true;
	}
	return payload;
}

function count(n: number, one: string, many: string): string {
	return `${n} ${n === 1 ? one : many}`;
}

/** Toast text for a successful bulk edit. */
export function bulkResultMessage(result: BatchUpdateResult): string {
	const r = {
		updated: result.updated ?? 0,
		unchanged: result.unchanged ?? 0,
		skipped: result.skipped ?? 0,
		schedulesUpdated: result.schedules_updated ?? 0,
		schedulesUnchanged: result.schedules_unchanged ?? 0,
		schedulesSkipped: result.schedules_skipped ?? 0
	};
	let message = `Updated ${count(r.updated, 'entry', 'entries')}`;
	if (r.unchanged) message += `, ${r.unchanged} already up to date`;
	if (r.skipped) message += `, ${r.skipped} skipped (deleted or missing)`;
	if (r.schedulesUpdated || r.schedulesUnchanged || r.schedulesSkipped) {
		message += ` · ${count(r.schedulesUpdated, 'recurring schedule', 'recurring schedules')} updated`;
		if (r.schedulesUnchanged) message += `, ${r.schedulesUnchanged} already up to date`;
		if (r.schedulesSkipped) message += `, ${r.schedulesSkipped} skipped`;
	}
	return message;
}

export type FetchAllResult<T> = { kind: 'rows'; rows: T[] } | { kind: 'tooMany' } | { kind: 'stale' };

/**
 * Page through every row matching the current filters ("select all
 * matching"). fetchPage must send the same filters and sort as the list.
 * - stale: isStale() turned true (filters or selection changed); drop it.
 * - tooMany: more than `cap` distinct rows match.
 * - rows: every matching row, deduplicated by id.
 * The page count is bounded, so a server that ignores offset can't loop forever.
 */
export async function fetchAllMatching<T extends { id: number }>(
	fetchPage: (offset: number, limit: number) => Promise<T[]>,
	{ pageSize = SELECT_ALL_PAGE_SIZE, cap = BULK_CAP, isStale }: { pageSize?: number; cap?: number; isStale: () => boolean }
): Promise<FetchAllResult<T>> {
	const rows = new Map<number, T>();
	const maxPages = Math.floor(cap / pageSize) + 1;
	for (let page = 0; page < maxPages; page++) {
		if (isStale()) return { kind: 'stale' };
		const batch = await fetchPage(page * pageSize, pageSize);
		if (isStale()) return { kind: 'stale' };
		for (const row of batch) if (!rows.has(row.id)) rows.set(row.id, row);
		if (rows.size > cap) return { kind: 'tooMany' };
		if (batch.length < pageSize) return { kind: 'rows', rows: [...rows.values()] };
	}
	return { kind: 'tooMany' };
}
