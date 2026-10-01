import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { get } from 'svelte/store';
import PasswordPage from '../routes/change-password/+page.svelte';
import { me, clearToken, clearApiCache } from './api/client';
import { goto } from '$app/navigation';
import { authenticated, setAuthUser, clearAuth } from './stores/auth';
import { setSharedOwner, sharedOwnerUserId } from './stores/shared';
import { setAccountFilter, currentAccountId } from './stores/accountFilter';

vi.mock('./api/client', () => ({
	me: { changePassword: vi.fn() }, clearToken: vi.fn(), clearApiCache: vi.fn(), isAuthenticated: vi.fn()
}));
vi.mock('$app/navigation', () => ({ goto: vi.fn().mockResolvedValue(undefined) }));
beforeEach(() => {
	vi.clearAllMocks(); localStorage.clear();
	setAuthUser('Alice', 1, false, false, true);
	setSharedOwner('2'); setAccountFilter(3);
	localStorage.setItem('lastUsedAccountId', '3');
});
afterEach(() => { cleanup(); clearAuth(); setSharedOwner(null); setAccountFilter(null); localStorage.clear(); });

async function submitPassword() {
	render(PasswordPage);
	await fireEvent.input(screen.getByLabelText('Current Password'), { target: { value: 'old-password' } });
	await fireEvent.input(screen.getByLabelText('New Password'), { target: { value: 'new-password' } });
	await fireEvent.input(screen.getByLabelText('Confirm New Password'), { target: { value: 'new-password' } });
	await fireEvent.click(screen.getByRole('button', { name: 'Change Password' }));
}

it('successful password change clears session and filters before routing to login', async () => {
	vi.mocked(me.changePassword).mockResolvedValue(undefined as any);
	await submitPassword();
	await waitFor(() => expect(goto).toHaveBeenCalledWith('/login?password_changed=1', { replaceState: true }));
	expect(clearToken).toHaveBeenCalledOnce(); expect(clearApiCache).toHaveBeenCalledOnce();
	expect(get(authenticated)).toBe(false);
	expect(get(sharedOwnerUserId)).toBeNull(); expect(get(currentAccountId)).toBeNull();
	expect(localStorage.getItem('lastUsedAccountId')).toBeNull();
	expect(localStorage.getItem('accountFilter:owner:2')).toBeNull();
});

it('failed password change preserves the active session for retry', async () => {
	vi.mocked(me.changePassword).mockRejectedValue(new Error('Current password is incorrect'));
	await submitPassword();
	await waitFor(() => expect(screen.getByText('Current password is incorrect')).toBeTruthy());
	expect(clearToken).not.toHaveBeenCalled(); expect(goto).not.toHaveBeenCalled();
	expect(get(authenticated)).toBe(true); expect(get(sharedOwnerUserId)).toBe('2');
});
