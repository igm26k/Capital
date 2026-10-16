import { createHash } from 'node:crypto';
import { test, expect } from '@playwright/test';

test('CSV preview, durable worker, identical purchases, repeat file and export', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`csv-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('CSV cash');
  await page.getByLabel('Начальный остаток, EUR').fill('100,00');
  await page.getByLabel('Дата открытия счета', { exact: true }).fill('2026-01-01T00:00');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByText('Импорт и экспорт CSV', { exact: true }).click();
  const panel = page.getByRole('region', { name: 'CSV импорт и экспорт', exact: true });
  const file = { name: 'synthetic.csv', mimeType: 'text/csv', buffer: Buffer.from('date,amount,payee,note\n2026-09-01,-1.23,Same shop,Purchase one\n2026-09-01,-1.23,Same shop,Purchase two\n2026-09-01,0,Invalid,Zero\n') };
  await panel.getByLabel('CSV файл').setInputFiles(file);
  await panel.getByLabel('Часовой пояс CSV').fill('UTC');
  await panel.getByRole('button', { name: 'Предпросмотр CSV', exact: true }).click();
  await expect(panel.getByText('Состояние: Готов к подтверждению', { exact: true })).toBeVisible();
  await expect(panel.getByLabel('Решение строки 2')).toHaveValue('create');
  await expect(panel.getByLabel('Решение строки 3')).toHaveValue('create');
  await expect(panel.getByLabel('Решение строки 4')).toHaveValue('skip');
  let confirmationDropped=false;
  await page.route('**/api/v1/workspaces/*/imports/*/confirm',async route=>{
    const response=await route.fetch();
    if(!confirmationDropped){confirmationDropped=true;await route.abort('connectionfailed');}
    else await route.fulfill({response});
  });
  await panel.getByRole('button', { name: 'Проверить подтверждение CSV', exact: true }).click();
  await panel.getByRole('button', { name: 'Подтвердить CSV импорт', exact: true }).click();
  await expect.poll(()=>confirmationDropped).toBe(true);
  await page.reload();
  await page.getByText('Импорт и экспорт CSV', { exact: true }).click();
  await panel.getByRole('button',{name:'Повторить запрос импорта',exact:true}).click();
  await expect(panel.getByRole('button',{name:'Повторить запрос импорта',exact:true})).toHaveCount(0);
  await expect(panel.getByText('Состояние: Завершён', { exact: true })).toBeVisible({ timeout: 30_000 });
  await expect(panel.getByText('Создано: 2; сопоставлено: 0; пропущено: 1', { exact: true })).toBeVisible();
  await expect(page.locator('.accounts strong')).toHaveText('97,54 EUR');
  await panel.getByLabel('CSV файл').setInputFiles(file);
  await panel.getByLabel('Часовой пояс CSV').fill('UTC');
  await panel.getByRole('button', { name: 'Предпросмотр CSV', exact: true }).click();
  await expect(panel.getByLabel('Решение строки 2')).toHaveValue('skip');
  // Explicitly asking to create again still resolves the exact source identity.
  await panel.getByLabel('Решение строки 2').selectOption('create');
  await panel.getByLabel('Решение строки 3').selectOption('create');
  await panel.getByRole('button', { name: 'Проверить подтверждение CSV', exact: true }).click();
  await panel.getByRole('button', { name: 'Подтвердить CSV импорт', exact: true }).click();
  await expect(panel.getByText('Создано: 0; сопоставлено: 2; пропущено: 1', { exact: true })).toBeVisible();
  await expect(page.locator('.accounts strong')).toHaveText('97,54 EUR');
  await panel.getByLabel('Экспорт от').fill('2026-09-01');
  await panel.getByLabel('Экспорт до').fill('2026-10-01');
  const download = page.waitForEvent('download');
  await panel.getByRole('button', { name: 'Скачать CSV', exact: true }).click();
  const result = await download;
  expect(result.suggestedFilename()).toBe('accounting.csv');
  expect(await result.failure()).toBeNull();
});


test('large CSV upload keeps the original request through a lost response and reload', async ({ page, context }) => {
  test.setTimeout(120_000);
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`csv-large-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Large CSV cash');
  await page.getByLabel('Начальный остаток, EUR').fill('100,00');
  await page.getByLabel('Дата открытия счета', { exact: true }).fill('2026-01-01T00:00');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByText('Импорт и экспорт CSV', { exact: true }).click();
  const panel=page.getByRole('region',{name:'CSV импорт и экспорт',exact:true});
  const content='date,amount,payee,padding\n2026-09-01,-1.00,Large shop,'+'x'.repeat(8*1024*1024)+'\n';
  await panel.getByLabel('CSV файл').setInputFiles({name:'large-synthetic.csv',mimeType:'text/csv',buffer:Buffer.from(content)});
  await panel.getByLabel('Часовой пояс CSV').fill('UTC');
  let dropped=false,replayed=false;
  const requests:{key:string|undefined;hash:string}[]=[];
  await page.route('**/api/v1/workspaces/*/imports',async route=>{
    if(route.request().method()!=='POST'){await route.continue();return;}
    requests.push({key:route.request().headers()['idempotency-key'],hash:createHash('sha256').update(route.request().postData()??'').digest('hex')});
    const response=await route.fetch();expect(response.status()).toBe(201);
    if(!dropped){dropped=true;await route.abort('connectionfailed');}else{replayed=response.headers()['idempotency-replayed']==='true';await route.fulfill({response});}
  });
  await panel.getByRole('button',{name:'Предпросмотр CSV',exact:true}).click();
  await expect.poll(()=>dropped,{timeout:30_000}).toBe(true);
  await expect(panel.getByRole('button',{name:'Повторить запрос импорта',exact:true})).toBeEnabled();
  await page.reload();await page.getByText('Импорт и экспорт CSV',{exact:true}).click();
  await panel.getByRole('button',{name:'Повторить запрос импорта',exact:true}).click();
  await expect(panel.getByText('Состояние: Готов к подтверждению',{exact:true})).toBeVisible({timeout:30_000});
  await expect.poll(()=>replayed).toBe(true);expect(requests).toHaveLength(2);expect(requests[1]).toEqual(requests[0]);
  const exported=await(await context.request.get('/api/v1/me/export')).json();
  expect(exported.workspaces[0].csv_imports).toHaveLength(1);
});

test('a stale CSV tab cannot overwrite another unknown upload',async({page,context})=>{
  await page.goto('/');await page.getByRole('button',{name:'Создать профиль',exact:true}).click();
  await page.getByLabel('Электронная почта').fill(`csv-cas-${crypto.randomUUID()}@example.test`);await page.getByLabel('Пароль',{exact:true}).fill('synthetic-browser-password-2026');
  await page.getByRole('button',{name:'Зарегистрироваться',exact:true}).click();await expect(page.getByRole('button',{name:'Выйти',exact:true})).toBeVisible();
  await page.getByLabel('Название счета').fill('CSV tab cash');await page.getByLabel('Начальный остаток, EUR').fill('100,00');await page.getByLabel('Дата открытия счета',{exact:true}).fill('2026-01-01T00:00');
  await page.getByRole('button',{name:'Создать счет',exact:true}).click();await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByText('Импорт и экспорт CSV',{exact:true}).click();const panel=page.getByRole('region',{name:'CSV импорт и экспорт',exact:true});
  const other=await context.newPage();await other.goto('/');await other.getByText('Импорт и экспорт CSV',{exact:true}).click();const second=other.getByRole('region',{name:'CSV импорт и экспорт',exact:true});
  const file={name:'tab.csv',mimeType:'text/csv',buffer:Buffer.from('date,amount,payee\n2026-10-01,-1.00,First upload\n')};
  await panel.getByLabel('CSV файл').setInputFiles(file);await panel.getByLabel('Часовой пояс CSV').fill('UTC');
  await second.getByLabel('CSV файл').setInputFiles({...file,buffer:Buffer.from('date,amount,payee\n2026-10-01,-2.00,Second upload\n')});await second.getByLabel('Часовой пояс CSV').fill('UTC');
  await expect(second.getByRole('button',{name:'Предпросмотр CSV',exact:true})).toBeEnabled();
  const requests:{key:string|undefined;body:string|null}[]=[];let all=0;context.on('request',r=>{if(r.method()==='POST'&&/\/imports$/.test(r.url()))all++;});
  await page.route('**/api/v1/workspaces/*/imports',async route=>{requests.push({key:route.request().headers()['idempotency-key'],body:route.request().postData()});const response=await route.fetch();if(requests.length===1)await route.abort('connectionfailed');else await route.fulfill({response});});
  await panel.getByRole('button',{name:'Предпросмотр CSV',exact:true}).click();await expect(panel.getByRole('button',{name:'Повторить запрос импорта',exact:true})).toBeVisible();
  await second.getByRole('button',{name:'Предпросмотр CSV',exact:true}).click();await expect(second.getByRole('alert')).toContainText('другой вкладке');expect(all).toBe(1);await other.close();
  await page.reload();await page.getByText('Импорт и экспорт CSV',{exact:true}).click();await panel.getByRole('button',{name:'Повторить запрос импорта',exact:true}).click();
  await expect(panel.getByText('Состояние: Готов к подтверждению',{exact:true})).toBeVisible();expect(requests).toHaveLength(2);expect(requests[1]).toEqual(requests[0]);
  await panel.getByRole('button',{name:'Проверить подтверждение CSV',exact:true}).click();await panel.getByRole('button',{name:'Подтвердить CSV импорт',exact:true}).click();await expect(panel.getByText('Состояние: Завершён',{exact:true})).toBeVisible();await expect(page.locator('.accounts strong')).toHaveText('99,00 EUR');
});

test('CSV confirmation refreshes new bank matches before creating money',async({page,context})=>{
  await page.goto('/');await page.getByRole('button',{name:'Создать профиль',exact:true}).click();
  await page.getByLabel('Электронная почта').fill(`csv-bank-${crypto.randomUUID()}@example.test`);await page.getByLabel('Пароль',{exact:true}).fill('synthetic-browser-password-2026');
  await page.getByRole('button',{name:'Зарегистрироваться',exact:true}).click();await expect(page.getByRole('button',{name:'Выйти',exact:true})).toBeVisible();
  await page.getByLabel('Название счета').fill('Bank CSV cash');await page.getByLabel('Начальный остаток, EUR').fill('100,00');await page.getByLabel('Дата открытия счета',{exact:true}).fill('2026-01-01T00:00');
  await page.getByRole('button',{name:'Создать счет',exact:true}).click();await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  const auth=await(await context.request.get('/api/v1/auth/session')).json(),root=`/api/v1/workspaces/${auth.workspace.id}`,account=(await(await context.request.get(`${root}/accounts`)).json()).items[0];
  await page.getByText('Импорт и экспорт CSV',{exact:true}).click();const panel=page.getByRole('region',{name:'CSV импорт и экспорт',exact:true});
  await panel.getByLabel('CSV файл').setInputFiles({name:'statement.csv',mimeType:'text/csv',buffer:Buffer.from('date,amount,payee\n2026-10-02,-1.00,Statement wording\n')});await panel.getByLabel('Часовой пояс CSV').fill('UTC');await panel.getByRole('button',{name:'Предпросмотр CSV',exact:true}).click();await expect(panel.getByLabel('Решение строки 2')).toHaveValue('create');
  const id=crypto.randomUUID(),response=await context.request.post(`${root}/source-events/auto`,{headers:{Origin:new URL(page.url()).origin,'X-CSRF-Token':auth.csrf_token,'X-Sync-Generation':auth.workspace.sync_generation_id,'Idempotency-Key':crypto.randomUUID()},data:{id,connection_id:crypto.randomUUID(),external_id:'e'.repeat(64),notification_key_hash:'f'.repeat(64),account_id:account.id,package_name:'example.bank',profile_id:'csv-cross-source',parser_version:1,kind:'expense',amount_minor:'100',currency:'EUR',observed_at:'2026-10-01T00:00:00Z',observed_timezone:'UTC',merchant:'Bank feed wording',card_last_four:null}});expect(response.status()).toBe(201);
  await expect.poll(async()=>{return(await(await context.request.get(`${root}/source-events/${id}`)).json()).state;}).toBe('applied');
  const transaction=(await(await context.request.get(`${root}/source-events/${id}`)).json()).transaction_id;
  await panel.getByRole('button',{name:'Проверить подтверждение CSV',exact:true}).click();await panel.getByRole('button',{name:'Подтвердить CSV импорт',exact:true}).click();
  await expect(panel.getByText(/Предпросмотр обновлён/)).toBeVisible();await expect(panel.getByLabel('Решение строки 2')).toHaveValue('skip');
  await panel.getByLabel('Решение строки 2').selectOption(`link:${transaction}`);await expect(panel.getByLabel('Решение строки 2')).toHaveValue(`link:${transaction}`);
  await panel.getByRole('button',{name:'Проверить подтверждение CSV',exact:true}).click();await panel.getByRole('button',{name:'Подтвердить CSV импорт',exact:true}).click();
  await expect(panel.getByText('Создано: 0; сопоставлено: 1; пропущено: 0',{exact:true})).toBeVisible();
  const balance=await(await context.request.get(`${root}/accounts/${account.id}`)).json();expect(balance.posted_balance_minor).toBe('10000');expect(balance.pending_delta_minor).toBe('-100');expect(balance.projected_balance_minor).toBe('9900');
});
