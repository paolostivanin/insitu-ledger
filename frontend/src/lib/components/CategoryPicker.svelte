<script lang="ts">
	import { categories, type Category, type CategoryInput } from '$lib/api/client';

	interface Props {
		cats: Category[];
		type: 'income' | 'expense';
		value: number;
		onchange: (id: number) => void;
		onCreated: (cat: Category) => void;
		id?: string;
	}

	let { cats, type, value, onchange, onCreated, id }: Props = $props();

	let showQuickCreate = $state(false);
	let newName = $state('');
	let newParentId = $state<number | null>(null);
	let newColor = $state('#6366f1');
	let creating = $state(false);
	let quickError = $state('');

	function filtered(): Category[] {
		return cats.filter(c => c.type === type);
	}

	function topLevel(): Category[] {
		return filtered().filter(c => c.parent_id === null);
	}

	function childrenOf(parentId: number): Category[] {
		return filtered().filter(c => c.parent_id === parentId);
	}

	function handleSelect(e: Event) {
		const val = (e.target as HTMLSelectElement).value;
		if (val === '__new__') {
			showQuickCreate = true;
			// Reset to previous valid value
			onchange(value);
		} else {
			showQuickCreate = false;
			onchange(parseInt(val, 10));
		}
	}

	function resetQuickForm() {
		newName = '';
		newParentId = null;
		newColor = '#6366f1';
		quickError = '';
		showQuickCreate = false;
	}

	async function createCategory(e: Event) {
		e.preventDefault();
		e.stopPropagation();
		if (!newName.trim()) return;
		creating = true;
		quickError = '';
		try {
			const input: CategoryInput = {
				name: newName.trim(),
				type,
				parent_id: newParentId,
				color: newColor
			};
			const res = await categories.create(input);
			// Reload categories and select the new one
			const updated = await categories.list();
			const newCat = updated.find(c => c.id === res.id);
			if (newCat) {
				onCreated(newCat);
				onchange(newCat.id);
			}
			resetQuickForm();
		} catch (err: any) {
			quickError = err.message || 'Failed to create category';
		}
		creating = false;
	}
</script>

<div class="cat-picker">
	<select {id} {value} onchange={handleSelect}>
		{#each topLevel() as parent}
			<option value={parent.id}>{parent.icon || ''} {parent.name}</option>
			{#each childrenOf(parent.id) as child}
				<option value={child.id}>&nbsp;&nbsp;↳ {child.icon || ''} {child.name}</option>
			{/each}
		{/each}
		<option disabled>──────────</option>
		<option value="__new__">+ New category...</option>
	</select>

	{#if showQuickCreate}
		<div class="quick-create">
			<form onsubmit={createCategory}>
				<div class="qc-row">
					<input
						type="text"
						bind:value={newName}
						placeholder="Category name"
						required
					/>
					<input type="color" bind:value={newColor} class="color-input" />
				</div>
				<div class="qc-row">
					<select bind:value={newParentId}>
						<option value={null}>Top level</option>
						{#each topLevel() as p}
							<option value={p.id}>Sub of: {p.name}</option>
						{/each}
					</select>
				</div>
				{#if quickError}
					<p class="qc-error">{quickError}</p>
				{/if}
				<div class="qc-actions">
					<button type="submit" class="btn-primary" disabled={creating}>
						{creating ? 'Creating...' : 'Create'}
					</button>
					<button type="button" class="btn-ghost" onclick={resetQuickForm}>Cancel</button>
				</div>
			</form>
		</div>
	{/if}
</div>

<style>
	.cat-picker {
		position: relative;
	}
	.cat-picker select {
		width: 100%;
	}
	.quick-create {
		position: absolute;
		top: 100%;
		left: 0;
		right: 0;
		z-index: 50;
		background: var(--bg-card);
		border: 1px solid var(--primary);
		border-radius: var(--radius);
		padding: 0.75rem;
		margin-top: 0.25rem;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
	}
	.qc-row {
		display: flex;
		gap: 0.5rem;
		margin-bottom: 0.5rem;
	}
	.qc-row input[type="text"] {
		flex: 1;
	}
	.qc-row select {
		width: 100%;
	}
	.color-input {
		width: 38px;
		height: 34px;
		padding: 2px;
		cursor: pointer;
	}
	.qc-actions {
		display: flex;
		gap: 0.5rem;
	}
	.qc-error {
		color: var(--danger);
		font-size: 0.8rem;
		margin-bottom: 0.5rem;
	}
</style>
