<script lang="ts">
	import { onMount } from 'svelte';
	import { categories, type Category } from '$lib/api/client';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';

	let cats = $state<Category[]>([]);
	let loading = $state(true);
	let showForm = $state(false);
	let editId = $state<number | null>(null);
	let error = $state('');
	let submitting = $state(false);

	// Confirm dialog
	let confirmOpen = $state(false);
	let confirmMessage = $state('');
	let confirmAction = $state(() => {});

	let fName = $state('');
	let fType = $state<'income' | 'expense'>('expense');
	let fParentId = $state<number | null>(null);
	let fColor = $state('#6366f1');
	let fIcon = $state('');

	onMount(load);

	async function load() {
		loading = true;
		cats = await categories.list();
		loading = false;
	}

	function parentCats(): Category[] {
		return cats.filter(c => c.parent_id === null && c.id !== editId);
	}

	function children(parentId: number): Category[] {
		return cats.filter(c => c.parent_id === parentId);
	}

	function topLevel(): Category[] {
		return cats.filter(c => c.parent_id === null);
	}

	function resetForm() {
		editId = null;
		fName = '';
		fType = 'expense';
		fParentId = null;
		fColor = '#6366f1';
		fIcon = '';
	}

	function startEdit(cat: Category) {
		editId = cat.id;
		fName = cat.name;
		fType = cat.type;
		fParentId = cat.parent_id;
		fColor = cat.color || '#6366f1';
		fIcon = cat.icon || '';
		showForm = true;
	}

	async function submit(e: Event) {
		e.preventDefault();
		error = '';
		submitting = true;
		const data = {
			name: fName,
			type: fType,
			parent_id: fParentId,
			color: fColor,
			icon: fIcon || undefined
		};
		try {
			if (editId) {
				await categories.update(editId, data);
			} else {
				await categories.create(data);
			}
			showForm = false;
			resetForm();
			await load();
		} catch (e: any) {
			error = e.message;
		}
		submitting = false;
	}

	function remove(id: number) {
		confirmMessage = 'Delete this category?';
		confirmAction = async () => {
			try {
				await categories.delete(id);
				await load();
			} catch (e: any) {
				error = e.message;
			}
		};
		confirmOpen = true;
	}
</script>

<div class="page">
	<div class="page-header">
		<h1>Categories</h1>
		<button class="btn-primary" onclick={() => { resetForm(); showForm = !showForm; }}>
			{showForm ? 'Cancel' : '+ New Category'}
		</button>
	</div>

	{#if error}
		<p class="error-msg">{error}</p>
	{/if}

	{#if showForm}
		<div class="card" style="margin-bottom: 1.5rem">
			<h2>{editId ? 'Edit' : 'New'} Category</h2>
			<form onsubmit={submit}>
				<div class="form-row">
					<div class="form-group">
						<label for="name">Name</label>
						<input id="name" type="text" bind:value={fName} required maxlength="100" />
					</div>
					<div class="form-group">
						<label for="type">Type</label>
						<select id="type" bind:value={fType}>
							<option value="expense">Expense</option>
							<option value="income">Income</option>
						</select>
					</div>
				</div>
				<div class="form-row">
					<div class="form-group">
						<label for="parent">Parent Category</label>
						<select id="parent" bind:value={fParentId}>
							<option value={null}>None (top level)</option>
							{#each parentCats() as p}
								<option value={p.id}>{p.name}</option>
							{/each}
						</select>
					</div>
					<div class="form-group">
						<label for="color">Color</label>
						<input id="color" type="color" bind:value={fColor} />
					</div>
					<div class="form-group">
						<label for="icon">Icon (emoji)</label>
						<input id="icon" type="text" bind:value={fIcon} placeholder="e.g. 🍔" />
					</div>
				</div>
				<button class="btn-primary" type="submit" disabled={submitting}>{submitting ? 'Saving...' : editId ? 'Update' : 'Create'}</button>
			</form>
		</div>
	{/if}

	{#if loading}
		<p>Loading...</p>
	{:else if cats.length === 0}
		<p class="empty-state">No categories yet. Create one to get started.</p>
	{:else}
		<div class="cat-grid">
			{#each topLevel() as cat}
				<div class="card cat-card">
					<div class="cat-header">
						<span class="cat-dot" style="background: {cat.color || '#6366f1'}"></span>
						<span class="cat-icon">{cat.icon || ''}</span>
						<span class="cat-name">{cat.name}</span>
						<span class="badge {cat.type === 'income' ? 'badge-income' : 'badge-expense'}">{cat.type}</span>
						<div class="actions">
							<button class="btn-ghost" onclick={() => startEdit(cat)}>Edit</button>
							<button class="btn-danger" onclick={() => remove(cat.id)}>Del</button>
						</div>
					</div>
					{#if children(cat.id).length > 0}
						<div class="sub-cats">
							{#each children(cat.id) as sub}
								<div class="sub-row">
									<span class="cat-dot small" style="background: {sub.color || cat.color || '#6366f1'}"></span>
									<span>{sub.icon || ''} {sub.name}</span>
									<div class="actions">
										<button class="btn-ghost" onclick={() => startEdit(sub)}>Edit</button>
										<button class="btn-danger" onclick={() => remove(sub.id)}>Del</button>
									</div>
								</div>
							{/each}
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>

<ConfirmDialog bind:open={confirmOpen} message={confirmMessage} onconfirm={confirmAction} />

<style>
	.page-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 1rem;
	}
	h2 { font-size: 1rem; margin-bottom: 1rem; }
	.cat-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(350px, 1fr));
		gap: 1rem;
	}
	.cat-header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.cat-dot {
		width: 12px;
		height: 12px;
		border-radius: 50%;
		flex-shrink: 0;
	}
	.cat-dot.small {
		width: 8px;
		height: 8px;
	}
	.cat-icon { font-size: 1.1rem; }
	.cat-name {
		font-weight: 600;
		flex: 1;
	}
	.sub-cats {
		margin-top: 0.75rem;
		padding-left: 1.5rem;
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}
	.sub-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}
	.sub-row span:nth-child(2) {
		flex: 1;
	}
</style>
