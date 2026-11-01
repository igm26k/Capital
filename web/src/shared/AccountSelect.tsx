import type { Account } from '../api/client';
import { SearchSelect } from './SearchSelect';
const colors = ['#167b68', '#2463af', '#8545a4', '#b64a32', '#466d30', '#3c578d'];
export function accountColor(id: string): string {
  return colors[[...id].reduce((sum, char) => sum + char.charCodeAt(0), 0) % colors.length];
}
export function AccountSelect({ label, value, accounts, onChange, name }: { label: string; value: string; accounts: Account[]; onChange: (value: string) => void; name?: string }) {
  return <SearchSelect label={label} name={name} value={value} onChange={onChange} options={accounts.map(a => ({
    value: a.id, label: a.name + ' · ' + a.currency,
    icon: <span className="account-color-dot" aria-hidden="true" style={{ backgroundColor: accountColor(a.id) }} />,
  }))} />;
}
