import { afterEach, expect, it, vi } from 'vitest';
import { render, fireEvent, screen, waitFor, cleanup } from '@testing-library/svelte';
import ConfirmDialog from './ConfirmDialog.svelte';

afterEach(cleanup);

function dialogBounds() {
	const dialog = screen.getByRole('dialog');
	vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({ left: 100, right: 400, top: 100, bottom: 300 } as DOMRect);
	return dialog;
}

it('closes on a backdrop click but leaves clicks in dialog padding alone', async () => {
	const onconfirm = vi.fn();
	render(ConfirmDialog, { open: true, onconfirm });
	const dialog = dialogBounds();
	await fireEvent.click(dialog, { clientX: 110, clientY: 110 });
	expect(screen.getByRole('dialog')).toBeTruthy();
	await fireEvent.click(dialog, { clientX: 50, clientY: 50 });
	expect(screen.queryByRole('dialog')).toBeNull();
	expect(onconfirm).not.toHaveBeenCalled();
});

it('ignores backdrop cancellation while an action is pending', async () => {
	let finish!: () => void;
	render(ConfirmDialog, { open: true, onconfirm: () => new Promise<void>(resolve => finish = resolve) });
	const dialog = dialogBounds();
	await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
	await fireEvent.click(dialog, { clientX: 50, clientY: 50 });
	expect(screen.getByRole('dialog')).toBeTruthy();
	finish();
	await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('keeps the dialog open while deleting, prevents duplicate submissions and closes on success', async () => {
	let finish!: () => void;
	const onconfirm = vi.fn(() => new Promise<void>(resolve => finish = resolve));
	render(ConfirmDialog, { open: true, message: 'Delete Rent?', onconfirm });
	const button = screen.getByRole('button', { name: 'Delete' });
	await fireEvent.click(button);
	expect(onconfirm).toHaveBeenCalledTimes(1);
	expect((button as HTMLButtonElement).disabled).toBe(true);
	expect(screen.getByRole('dialog')).toBeTruthy();
	finish();
	await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('shows a failed deletion and permits retry', async () => {
	const onconfirm = vi.fn().mockRejectedValueOnce(new Error('Server unavailable')).mockResolvedValueOnce(undefined);
	render(ConfirmDialog, { open: true, onconfirm });
	await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
	await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Server unavailable'));
	await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
	await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
	expect(onconfirm).toHaveBeenCalledTimes(2);
});

it('focuses Cancel and restores the opener without deleting on cancel', async () => {
	const opener = document.createElement('button');
	document.body.append(opener); opener.focus();
	const onconfirm = vi.fn();
	render(ConfirmDialog, { open: true, onconfirm });
	const cancel = screen.getByRole('button', { name: 'Cancel' });
	expect(document.activeElement).toBe(cancel);
	await fireEvent.click(cancel);
	expect(onconfirm).not.toHaveBeenCalled();
	expect(document.activeElement).toBe(opener);
	opener.remove();
});
