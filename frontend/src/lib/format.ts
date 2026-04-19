// formatMoney returns the amount formatted with the user's chosen symbol.
// The symbol may be empty (renders as a plain number).
export function formatMoney(amount: number, symbol: string): string {
	const n = amount.toLocaleString(undefined, {
		minimumFractionDigits: 2,
		maximumFractionDigits: 2
	});
	if (!symbol) return n;
	return `${symbol} ${n}`;
}
