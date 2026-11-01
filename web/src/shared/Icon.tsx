export type Name = 'menu' | 'refresh' | 'settings' | 'plus' | 'bank' | 'card' | 'cash' | 'records' | 'arrow' | 'expense' | 'income' | 'transfer' | 'food' | 'shop' | 'home' | 'transport' | 'health' | 'travel' | 'education' | 'leisure' | 'work' | 'category';
const paths: Record<Name, string> = {
  food: "M4 3v6a2 2 0 0 0 4 0V3M6 3v18M16 3v18M16 3c5 4 5 9 0 9",
  shop: "M4 7h16l1 14H3L4 7M8 7V5a4 4 0 0 1 8 0v2",
  home: "M3 11l9-8 9 8M5 10v11h14V10M10 21v-7h4v7",
  transport: "M4 15l1-7h14l1 7M3 15h18v5H3zM6 20v2M18 20v2M6 17h2M16 17h2",
  health: "M9 3h6v6h6v6h-6v6H9v-6H3V9h6z",
  travel: "M3 10l7 2 5-9 3 1-3 9 6 4-1 2-8-2-5 4-2-1 2-5-5-3z",
  education: "M2 8l10-5 10 5-10 5-10-5M6 10v7q6 5 12 0v-7M22 8v9",
  leisure: "M5 7h14l3 13h-5l-3-4h-4l-3 4H2L5 7M6 11v4M4 13h4M16 12h.01M19 14h.01",
  work: "M3 7h18v14H3zM8 7V3h8v4M3 12h18M10 12v3h4v-3",
  category: "M12 2l10 10-10 10L2 12 12 2M12 8v8M8 12h8",
  menu: 'M4 6h16M4 12h16M4 18h16', refresh: 'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',
  plus: 'M12 5v14M5 12h14', bank: 'M3 9l9-6 9 6H3M5 10v8M10 10v8M14 10v8M19 10v8M3 21h18',
  card: 'M3 5h18v14H3zM3 10h18M7 15h3', cash: 'M3 6h18v12H3zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6M6 12h.01M18 12h.01',
  records: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01', arrow: 'M5 12h14M14 7l5 5-5 5',
  expense: 'M7 7l10 10M7 17h10V7', income: 'M7 7l10 10M7 17V7h10', transfer: 'M4 8h16l-4-4M20 16H4l4 4',
};
export function Icon({ name }: { name: Name }) { return <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>; }
