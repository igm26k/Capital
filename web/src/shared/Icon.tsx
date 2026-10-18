type Name = 'menu' | 'refresh' | 'settings' | 'plus' | 'bank' | 'card' | 'cash' | 'records' | 'arrow' | 'expense' | 'income' | 'transfer';
const paths: Record<Name, string> = {
  menu: 'M4 6h16M4 12h16M4 18h16', refresh: 'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',
  plus: 'M12 5v14M5 12h14', bank: 'M3 9l9-6 9 6H3M5 10v8M10 10v8M14 10v8M19 10v8M3 21h18',
  card: 'M3 5h18v14H3zM3 10h18M7 15h3', cash: 'M3 6h18v12H3zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6M6 12h.01M18 12h.01',
  records: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01', arrow: 'M5 12h14M14 7l5 5-5 5',
  expense: 'M7 7l10 10M7 17h10V7', income: 'M7 7l10 10M7 17V7h10', transfer: 'M4 8h16l-4-4M20 16H4l4 4',
};
export function Icon({ name }: { name: Name }) { return <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>; }
