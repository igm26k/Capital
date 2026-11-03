import { test, expect } from '@playwright/test';
test('summary includes every filtered page, separates currencies and excludes FX transfers', async ({ page }) => {
  const workspace = { id: 'workspace', name: 'Личное пространство', sync_generation_id: 'generation' };
  const operation = (id: string, kind: string, entries: object[]) => ({ id, kind, entries, allocations: [], tag_ids: [], occurred_at: '2026-11-03T09:00:00Z', note: '', payee: '', status: 'posted' });
  const first = [operation('expense', 'expense', [{account_id:'eur',currency:'EUR',amount_minor:'-2000'}]), operation('transfer', 'transfer', [{account_id:'eur',currency:'EUR',amount_minor:'-30000'},{account_id:'usd',currency:'USD',amount_minor:'32000'}])];
  const second = [operation('refund', 'refund', [{account_id:'eur',currency:'EUR',amount_minor:'500'}]), operation('income', 'income', [{account_id:'eur',currency:'EUR',amount_minor:'5000'}]), operation('usd', 'expense', [{account_id:'usd',currency:'USD',amount_minor:'-1250'}])];
  const queries: string[] = [];
  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url()), path = url.pathname;
    let body: unknown = {items:[],next_cursor:null};
    if(path.endsWith('/auth/session')) body={profile:{id:'owner',email:'summary@example.test',timezone:'Europe/Moscow'},session:{id:'session'},workspace,csrf_token:'csrf'};
    else if(path.endsWith('/workspaces')) body={items:[workspace],next_cursor:null};
    else if(path.endsWith('/transactions')) { queries.push(url.search); body={items:url.searchParams.has('cursor')?second:first,next_cursor:url.searchParams.has('cursor')?null:'second'}; }
    return route.fulfill({json:body});
  });
  await page.goto('/#operations');
  const summary=page.getByLabel('Финансовая сводка');
  await expect(summary).toContainText('−15,00 €'); await expect(summary).toContainText('50,00 €'); await expect(summary).toContainText('35,00 €'); await expect(summary).toContainText('−12,50 $');
  await expect(page.getByTestId('transaction-income')).toHaveCount(0);
  expect(queries.some(q=>q.includes('cursor=second'))).toBe(true);
  await page.getByLabel('Расширенные фильтры', {exact:true}).click();
  await page.getByLabel('Поиск в истории').fill('магазин'); await page.getByRole('button',{name:'Применить фильтры'}).click();
  await expect.poll(()=>queries.filter(q=>q.includes('q=')).length).toBeGreaterThanOrEqual(3);
  expect(queries.filter(q=>q.includes('q=')).every(q=>new URLSearchParams(q).get('q')==='магазин')).toBe(true);
});
