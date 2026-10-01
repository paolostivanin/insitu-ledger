import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import ReportsPage from '../routes/reports/+page.svelte';
import { reports } from './api/client';
import { sharedOwnerUserId } from './stores/shared';

const charts = vi.hoisted(() => [] as Array<{ setOption: ReturnType<typeof vi.fn> }>);
vi.mock('echarts', () => ({ init: vi.fn(() => {
	const chart = { setOption: vi.fn(), on: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
	charts.push(chart); return chart;
}) }));
vi.mock('./api/client', () => ({ reports: {
	byCategory: vi.fn(), byMonth: vi.fn(), trend: vi.fn(), summary: vi.fn()
} }));
beforeEach(() => {
	vi.resetAllMocks(); charts.length = 0; sharedOwnerUserId.set(null);
	vi.mocked(reports.byMonth).mockResolvedValue([]);
	vi.mocked(reports.trend).mockResolvedValue([]);
});
afterEach(cleanup);

it('a failed report ends loading and can be retried independently', async () => {
	vi.mocked(reports.byCategory).mockRejectedValueOnce(new Error('Report unavailable')).mockResolvedValueOnce([]);
	render(ReportsPage);
	await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Report unavailable'));
	expect(screen.queryByText('Loading category report…')).toBeNull();
	await fireEvent.click(screen.getByRole('button', { name: 'Retry category report' }));
	await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
	expect(reports.byCategory).toHaveBeenCalledTimes(2);
	expect(reports.byMonth).toHaveBeenCalledTimes(1);
});

it('an older owner report cannot replace the current chart', async () => {
	let finish!: (value: any[]) => void;
	vi.mocked(reports.byCategory).mockImplementationOnce(() => new Promise(resolve => finish = resolve))
		.mockResolvedValueOnce([{ category_id: 2, category_name: 'Current', total: 25 } as any]);
	render(ReportsPage);
	await waitFor(() => expect(reports.byCategory).toHaveBeenCalledTimes(1));
	sharedOwnerUserId.set('2');
	await waitFor(() => expect(charts[0].setOption.mock.lastCall?.[0].series[0].data[0]?.name).toBe('Current'));
	finish([{ category_id: 1, category_name: 'Previous', total: 50 }]);
	await Promise.resolve();
	expect(charts[0].setOption.mock.lastCall?.[0].series[0].data[0]?.name).toBe('Current');
	expect(reports.byCategory).toHaveBeenLastCalledWith(expect.objectContaining({ owner_id: '2' }));
});
