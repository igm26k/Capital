import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./tests/email-auth',workers:1,timeout:90_000,reporter:'list',outputDir:'../ops/.runtime/email-e2e',use:{baseURL:'https://localhost:8446',ignoreHTTPSErrors:true,trace:'off',screenshot:'off',launchOptions:{executablePath:process.env.CHROME_PATH??'/usr/bin/google-chrome',args:['--no-sandbox']}}});
