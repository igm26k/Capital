import { test, expect } from '@playwright/test';
import { parseBankMessage, validateBankRule, type BankRule } from '../../src/features/sources/bankMessages';
const rule: BankRule = { id: '00000000-0000-4000-8000-000000000001', bank: 'Мой банк', channel: 'sms', sender: 'BANK', accountId: '00000000-0000-4000-8000-000000000002', currency: 'EUR', kind: 'expense', template: 'Оплата {amount} {currency} в {merchant}, карта *{card}', decimalSeparator: ',', cardLastFour: '1234', enabled: true };
const message = (text: string) => ({ channel: 'sms' as const, sender: 'bank', text, observedAt: '2026-10-19T10:00:00Z' });
test('bank rules parse exact money, currencies, merchant and bound card', () => {
  expect(parseBankMessage(rule, message('Оплата 1 234,56 € в Кофейня, карта *1234'))).toMatchObject({ amountMinor: '123456', merchant: 'Кофейня', currency: 'EUR', cardLastFour: '1234', kind: 'expense' });
  expect(parseBankMessage({ ...rule, decimalSeparator: '.', channel: 'push' }, { ...message('Оплата 1,234.56 EUR в Магазин, карта *1234'), channel: 'push' })).toMatchObject({ amountMinor: '123456' });
  expect(parseBankMessage(rule, message('Оплата 1.234,56 EUR в Магазин, карта *1234')).amountMinor).toBe('123456');
});
test('rules reject foreign senders, wrong card/currency, OTP and invalid precision', () => {
  const text = 'Оплата 12,34 EUR в Магазин, карта *1234';
  for (const value of [text.replace('EUR', 'USD'), text.replace('1234', '9999'), text.replace('12,34', '12,345'), text.replace('12,34', '1 23,45'), text + '\nКод подтверждения 123456', text.replace('Магазин', '4111 1111 1111 1111')]) expect(() => parseBankMessage(rule, message(value))).toThrow();
  expect(() => parseBankMessage(rule, { ...message(text), sender: 'OTHER' })).toThrow(/Отправитель/);
  expect(() => parseBankMessage({ ...rule, enabled: false }, message(text))).toThrow(/выключено/);
  expect(() => parseBankMessage(rule, { ...message(text), channel: 'push' })).toThrow(/тип сообщения/);
});
test('templates escape regex literals and reject unknown, repeated or adjacent tokens', () => {
  for (const template of ['{amount}{currency}', '{amount} {amount}', '{amount} {password}', 'Без суммы', '{amount} {']) expect(() => validateBankRule({ ...rule, template })).toThrow();
  expect(parseBankMessage({ ...rule, cardLastFour: '', template: 'Покупка (карта): {amount} EUR.' }, message('Покупка (карта): 1,20 EUR.')).amountMinor).toBe('120');
  expect(() => parseBankMessage({ ...rule, cardLastFour: '', template: 'Покупка (карта): {amount} EUR.' }, message('Покупка карта: 1,20 EURx'))).toThrow();
});
test('JPY, KWD and maximum values retain their minor units without floating point', () => {
  const base = { ...rule, template: '{amount} {currency}', cardLastFour: '' };
  expect(parseBankMessage({ ...base, currency: 'JPY' }, message('123 JPY')).amountMinor).toBe('123');
  expect(parseBankMessage({ ...base, currency: 'KWD' }, message('1,234 KWD')).amountMinor).toBe('1234');
  expect(() => parseBankMessage(base, message('0 EUR'))).toThrow();
  expect(() => parseBankMessage(base, message('90000000000001 EUR'))).toThrow();
});
