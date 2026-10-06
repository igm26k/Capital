import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/e2e', workers: 1, timeout: 60_000, fullyParallel: false,
  reporter: 'list', outputDir: '../ops/.runtime/e2e',
  use: {
    baseURL: 'https://localhost:8444',
    ignoreHTTPSErrors: true, trace: 'off', screenshot: 'off',
    launchOptions: { executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome', args: ['--no-sandbox'] },
  },
});
