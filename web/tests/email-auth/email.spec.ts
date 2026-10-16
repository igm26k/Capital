import { test, expect } from '@playwright/test';
test('verified registration and password recovery use real API and revoke another browser',async({page,context,browser})=>{
 const email=`verified-browser-${crypto.randomUUID()}@example.test`,password='synthetic-initial-browser-password',newPassword='synthetic-new-browser-password';
 async function code(previous=''){let value='';await expect.poll(async()=>{value=(await(await context.request.get(`/test/code?email=${encodeURIComponent(email)}`)).json()).code;return value!==previous?value.length:0;}).toBe(43);return value;}
 await page.goto('/');await page.getByRole('button',{name:'Создать профиль',exact:true}).click();await page.getByLabel('Электронная почта').fill(email);await page.getByLabel('Пароль',{exact:true}).fill(password);await page.getByRole('button',{name:'Зарегистрироваться',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Подтвердить электронную почту'})).toBeVisible();expect((await context.cookies()).find(c=>c.name==='__Host-accounting_session')).toBeUndefined();
 const registrationCode=await code();await page.getByLabel('Код из письма').fill(registrationCode);await page.getByRole('button',{name:'Подтвердить регистрацию',exact:true}).click();await expect(page.getByRole('button',{name:'Выйти',exact:true})).toBeVisible();
 const other=await browser.newContext({ignoreHTTPSErrors:true});try{
 const otherPage=await other.newPage();await otherPage.goto('https://localhost:8446');await otherPage.getByLabel('Электронная почта').fill(email);await otherPage.getByLabel('Пароль',{exact:true}).fill(password);await otherPage.getByRole('button',{name:'Войти',exact:true}).click();await expect(otherPage.getByRole('button',{name:'Выйти',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Выйти',exact:true}).click();await page.getByRole('button',{name:'Забыли пароль?',exact:true}).click();await page.getByLabel('Электронная почта').fill(email);await page.getByRole('button',{name:'Отправить код восстановления',exact:true}).click();
 const recoveryCode=await code(registrationCode);expect(recoveryCode).not.toBe(registrationCode);await page.getByLabel('Код из письма').fill('a'.repeat(43));await page.getByLabel('Пароль',{exact:true}).fill(newPassword);await page.getByRole('button',{name:'Сохранить новый пароль',exact:true}).click();await expect(page.getByRole('alert')).toContainText('Код недействителен');
 await page.getByLabel('Код из письма').fill(recoveryCode);await page.getByRole('button',{name:'Сохранить новый пароль',exact:true}).click();await expect(page.getByRole('alert')).toContainText('Пароль изменён');
 expect((await other.request.get('https://localhost:8446/api/v1/auth/session')).status()).toBe(401);await otherPage.reload();await expect(otherPage.getByRole('button',{name:'Войти',exact:true})).toBeVisible();
 await page.getByLabel('Электронная почта').fill(email);await page.getByLabel('Пароль',{exact:true}).fill(newPassword);await page.getByRole('button',{name:'Войти',exact:true}).click();await expect(page.getByRole('button',{name:'Выйти',exact:true})).toBeVisible();
 }finally{await other.close();}
 await context.request.post('/test/complete');
});
