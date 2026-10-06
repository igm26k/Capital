const scales: Record<string, number> = { EUR: 2, USD: 2, GBP: 2, RUB: 2, JPY: 0, KWD: 3 };
export function minor(value: string, currency = 'EUR'): string {
  const scale = scales[currency];
  if (scale === undefined || !/^-?\d+(?:[.,]\d+)?$/.test(value)) throw new Error('Введите сумму цифрами, например 12,34.');
  const negative = value.startsWith('-');
  const [whole, fraction = ''] = value.replace(/^-/, '').split(/[.,]/);
  if (fraction.length > scale) throw new Error(`Для ${currency} допустимо знаков после запятой: ${scale}.`);
  const amount = BigInt(whole) * 10n ** BigInt(scale) + BigInt(fraction.padEnd(scale, '0') || '0');
  if (amount > 9000000000000000n) throw new Error('Сумма выходит за допустимый диапазон.');
  return (negative ? -amount : amount).toString();
}
export function money(value: string, currency: string): string {
  const scale = scales[currency];
  if (scale === undefined) return `${value} minor ${currency}`;
  const amount = BigInt(value), digits = (amount < 0n ? -amount : amount).toString().padStart(scale + 1, '0');
  return `${amount < 0n ? '−' : ''}${scale ? digits.slice(0, -scale) + ',' + digits.slice(-scale) : digits} ${currency}`;
}
