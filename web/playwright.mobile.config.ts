import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/mobile', workers: 1, reporter: 'list',
  outputDir: '../ops/.runtime/mobile-ui',
  use: { baseURL: 'http://127.0.0.1:4175', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
    launchOptions: { executablePath: process.env.CHROME_PATH ?? '/usr/bin/google-chrome', args: ['--no-sandbox'] } },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 4175', url: 'http://127.0.0.1:4175', reuseExistingServer: false },
});
