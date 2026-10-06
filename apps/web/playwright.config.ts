import { defineConfig, devices } from '@playwright/test'

// The end-to-end tests drive the console of a running stack (docker compose up --build --wait), through nginx as a
// user would. They create their own project, so they can run against a stack that already holds other work.
export default defineConfig({
  testDir: './e2e',
  // Jobs run on real workers; the slowest flow waits for a retry with backoff.
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env['CI']),
  retries: 0,
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env['QUANTARUN_E2E_URL'] ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
