import { readCSVCommand, compareAuxiliaryCommand } from '../imports/commandStorage';
import { validateBankRule, type BankRule } from './bankMessages';
export type BankRules = { key: string; rules: BankRule[] };
export const bankRulesKey = (owner: string, workspace: string) => `accounting.bank-rules.v1:${owner}:${workspace}`;
export async function readBankRules(key: string): Promise<BankRules | null> {
  const value = await readCSVCommand<BankRules>(key);
  if (value) {
    if (typeof value.key !== 'string' || !Array.isArray(value.rules) || value.rules.length > 50 || new Set(value.rules.map(r => r.id)).size !== value.rules.length) throw new Error('Не удалось прочитать правила банков. Исходные настройки сохранены.');
    value.rules.forEach(validateBankRule);
  }
  return value;
}
export async function saveBankRules(key: string, expected: string | null, rules: BankRule[]): Promise<BankRules> {
  if (rules.length > 50 || new Set(rules.map(r => r.id)).size !== rules.length) throw new Error('Можно сохранить до 50 правил банков.');
  rules.forEach(validateBankRule);
  const value = { key: crypto.randomUUID(), rules };
  await compareAuxiliaryCommand(key, expected, value);
  return value;
}
