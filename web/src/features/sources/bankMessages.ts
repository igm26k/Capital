import { minor, currencies } from '../../shared/money';

export type BankRule = {
  id: string; bank: string; channel: 'sms' | 'push'; sender: string; accountId: string;
  currency: string; kind: 'expense' | 'income'; template: string; decimalSeparator: ',' | '.';
  cardLastFour: string; enabled: boolean;
};
export type ParsedBankMessage = { ruleId: string; accountId: string; kind: 'expense' | 'income'; amountMinor: string; currency: string; merchant: string; cardLastFour: string | null };
export type BankMessage = { channel: 'sms' | 'push'; sender: string; text: string; observedAt: string };
export type SourceIntake = { id: string; connection_id: string; external_id: string; account_id: string; package_name: string; profile_id: string; parser_version: number; kind: string; amount_minor: string; currency: string; observed_at: string; observed_timezone: string; merchant: string; card_last_four: string | null };
const token = /\{([a-zA-Z]+)\}/g;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const secret = /\botp\b|one[ -]time|verification code|security code|код(?:ом)?[ :]*(?:подтверждения|входа|из смс)|одноразов|пароль/iu;
const fullCard = /[0-9](?:[ -]?[0-9]){11,}/;
const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
export const normalizeSender = (value: string) => value.normalize('NFKC').trim().toLocaleLowerCase('en-US');
const normalizedText = (value: string) => value.replace(/\r\n?/g, '\n').trim();

export function validateBankRule(rule: BankRule): void {
  if (!uuid.test(rule.id) || !uuid.test(rule.accountId) || !['sms', 'push'].includes(rule.channel) || !['income', 'expense'].includes(rule.kind) || !currencies.includes(rule.currency) || ![',', '.'].includes(rule.decimalSeparator) || typeof rule.enabled !== 'boolean') throw new Error('Проверьте счет, валюту и тип сообщения.');
  if (!rule.bank.trim() || rule.bank.length > 80 || !rule.sender.trim() || rule.sender.length > 100 || /[\p{Cc}]/u.test(rule.bank + rule.sender)) throw new Error('Укажите банк и отправителя без переносов строк (до 80 и 100 символов).');
  const template = normalizedText(rule.template), tokens = [...template.matchAll(token)], names = tokens.map(t => t[1]);
  if (template.length < 3 || template.length > 1000 || !names.includes('amount') || new Set(names).size !== names.length || names.some(n => !['amount', 'currency', 'merchant', 'card'].includes(n)) || /[{}]/.test(template.replace(token, '')) || tokens.some((t, i) => i > 0 && t.index === tokens[i - 1].index! + tokens[i - 1][0].length)) throw new Error('В шаблоне нужен {amount}. Допустимы также {currency}, {merchant}, {card}: по одному разу, с текстом или пробелом между ними.');
  if (secret.test(template) || fullCard.test(template)) throw new Error('Используйте шаблон платежа без кодов подтверждения и полного номера карты.');
  if (rule.cardLastFour && (!/^\d{4}$/.test(rule.cardLastFour) || !names.includes('card'))) throw new Error('Для фильтра по карте нужны четыре цифры и {card} в шаблоне.');
}

function amountMinor(value: string, rule: BankRule): string {
  const clean = value.replace(/[\u00a0\u202f]/g, ' ').trim();
  const decimal = rule.decimalSeparator, grouping = decimal === ',' ? '.' : ',';
  const pieces = clean.split(decimal);
  if (pieces.length > 2) throw new Error('Сумма не соответствует выбранному десятичному разделителю.');
  const whole = pieces[0], fraction = pieces[1];
  const grouped = new RegExp(`^[0-9]{1,3}(?:${escape(grouping)}[0-9]{3})+$`);
  if (!/^[0-9]+$/.test(whole) && !/^[0-9]{1,3}(?: [0-9]{3})+$/.test(whole) && !grouped.test(whole)) throw new Error('Не удалось распознать разделители в сумме. Проверьте правило банка.');
  if (fraction !== undefined && !/^[0-9]+$/.test(fraction)) throw new Error('Не удалось распознать дробную часть суммы.');
  const result = minor(whole.split(grouping).join('').replaceAll(' ', '') + (fraction === undefined ? '' : '.' + fraction), rule.currency);
  if (BigInt(result) <= 0n) throw new Error('Сумма платежа должна быть больше нуля.');
  return result;
}

export function parseBankMessage(rule: BankRule, message: BankMessage): ParsedBankMessage {
  validateBankRule(rule);
  if (!rule.enabled) throw new Error('Правило этого банка выключено.');
  if (rule.channel !== message.channel || normalizeSender(rule.sender) !== normalizeSender(message.sender)) throw new Error('Отправитель или тип сообщения не совпадает с правилом банка.');
  const text = normalizedText(message.text);
  if (!text || text.length > 8192) throw new Error('Вставьте одно сообщение длиной до 8192 символов.');
  if (secret.test(text) || fullCard.test(text)) throw new Error('Это сообщение содержит код подтверждения или полный номер карты. Оно не будет импортировано.');
  const template = normalizedText(rule.template), tokens = [...template.matchAll(token)];
  let pattern = '^', position = 0;
  for (const match of tokens) {
    pattern += escape(template.slice(position, match.index));
    pattern += ({ amount: '([0-9][0-9 .,\\u00a0\\u202f]{0,39})', currency: '([A-Z]{3}|€|\\$|£|₽|¥)', merchant: '([^\\r\\n]{1,120}?)', card: '([0-9]{4})' } as Record<string, string>)[match[1]];
    position = match.index! + match[0].length;
  }
  pattern += escape(template.slice(position)) + '$(?![\\s\\S])';
  const result = new RegExp(pattern, 'u').exec(text);
  if (!result) throw new Error('Сообщение не совпало с шаблоном. Проверьте текст, пробелы и правило банка.');
  const fields = Object.fromEntries(tokens.map((t, index) => [t[1], result[index + 1]]));
  const currency = ({ '€': 'EUR', '$': 'USD', '£': 'GBP', '₽': 'RUB', '¥': 'JPY' } as Record<string, string>)[fields.currency] ?? fields.currency ?? rule.currency;
  if (currency !== rule.currency) throw new Error('Валюта сообщения отличается от валюты выбранного счета.');
  if (rule.cardLastFour && fields.card !== rule.cardLastFour) throw new Error('Сообщение относится к другой карте.');
  const merchant = fields.merchant?.trim() ?? '';
  if (/[\p{Cc}]/u.test(merchant)) throw new Error('Не удалось распознать получателя платежа.');
  return { ruleId: rule.id, accountId: rule.accountId, kind: rule.kind, amountMinor: amountMinor(fields.amount, rule), currency, merchant, cardLastFour: fields.card ?? null };
}

async function digest(value: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].map(v => v.toString(16).padStart(2, '0')).join('');
}
export async function sourcePackage(channel: BankMessage['channel'], sender: string): Promise<string> {
  return `web.${channel}.s${await digest(normalizeSender(sender))}`;
}
export async function bankMessageIntake(rule: BankRule, message: BankMessage): Promise<SourceIntake> {
  const parsed = parseBankMessage(rule, message), observedAt = new Date(message.observedAt);
  if (!Number.isFinite(observedAt.getTime())) throw new Error('Укажите дату и время исходного сообщения.');
  const source = await sourcePackage(message.channel, message.sender);
  // Identity includes the original timestamp and text, never just the amount or chosen account.
  const external = await digest(JSON.stringify([1, message.channel, normalizeSender(message.sender), observedAt.toISOString(), normalizedText(message.text)]));
  return { id: crypto.randomUUID(), connection_id: rule.id, external_id: external, account_id: parsed.accountId, package_name: source, profile_id: rule.id, parser_version: 1, kind: parsed.kind, amount_minor: parsed.amountMinor, currency: parsed.currency, observed_at: observedAt.toISOString(), observed_timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', merchant: parsed.merchant, card_last_four: parsed.cardLastFour };
}
