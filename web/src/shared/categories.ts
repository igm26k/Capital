import type { Category } from '../api/client';
// Labels keep archived ancestors visible so historical categories remain identifiable.
export function categoryLabel(category: Category, categories: Category[]): string {
  const names = [category.name], visited = new Set([category.id]);
  let parent = category.parent_id;
  while (parent && !visited.has(parent)) {
    visited.add(parent);
    const item = categories.find(value => value.id === parent);
    if (!item) break;
    names.unshift(item.name); parent = item.parent_id;
  }
  return names.join(' / ');
}
export function canParent(candidate: Category, child: string | undefined, categories: Category[]): boolean {
  const visited = new Set<string>();
  let item: Category | undefined = candidate;
  while (item) {
    if (item.id === child || visited.has(item.id)) return false;
    visited.add(item.id); item = categories.find(value => value.id === item!.parent_id);
  }
  return !candidate.archived_at;
}
