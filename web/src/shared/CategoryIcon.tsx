import type { Category } from '../api/client';
import { Icon, type Name } from './Icon';
const rules: [Name, RegExp][] = [
  ['food', /продукт|еда|питан|ресторан|кафе|кофе|food|grocer|restaurant|coffee/i],
  ['transport', /транспорт|авто|бензин|такси|метро|парков|transport|car|fuel|taxi/i],
  ['home', /дом|жиль|квартир|коммун|аренд|home|house|rent|utilit/i],
  ['health', /здоров|медиц|аптек|врач|health|medic|pharmacy/i],
  ['travel', /путеше|отпуск|билет|отел|travel|hotel|flight/i],
  ['education', /образов|учеб|книг|курс|education|book|school/i],
  ['leisure', /развлеч|спорт|кино|хобби|досуг|entertain|sport|cinema|hobby/i],
  ['work', /работ|зарплат|доход|бизнес|work|salary|income|business/i],
  ['shop', /покуп|одежд|обув|техник|shop|cloth|electronic/i]
];
export function categoryAppearance(id: string | null | undefined, categories: Category[]) {
  let item = categories.find(c => c.id === id);
  const name = item?.name ?? '';
  const seen = new Set<string>();
  while (item?.parent_id && !seen.has(item.id)) {
    seen.add(item.id);
    const parent = categories.find(c => c.id === item!.parent_id);
    if (!parent) break;
    item = parent;
  }
  let hash = 2166136261;
  for (const char of item?.id ?? '') hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0;
  const hue = (hash % 360000) / 1000;
  const color = id ? `hsl(${hue} 55% 40%)` : '#777777';
  const icon: Name = rules.find(([, rule]) => rule.test(name))?.[0] ?? rules.find(([, rule]) => rule.test(item?.name ?? ''))?.[0] ?? 'category';
  return { color, icon };
}
export function CategoryIcon({ id, categories }: { id?: string | null; categories: Category[] }) {
  const appearance = categoryAppearance(id, categories);
  return <span className="category-icon" style={{ color: appearance.color }}><Icon name={appearance.icon} /></span>;
}
