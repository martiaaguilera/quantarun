import { expect, type APIRequestContext, type Page } from '@playwright/test'

/** The operator token of the stack under test; the default is the one in .env.example. */
export const ADMIN_TOKEN =
  process.env['QUANTARUN_ADMIN_TOKEN'] ?? 'local-dev-admin-token-change-me-0123456789'

const admin = { Authorization: `Bearer ${ADMIN_TOKEN}` }

export interface Tenant {
  projectId: string
  key: string
}

/** A fresh project with its own key, so assertions only ever see this test's jobs. */
export async function createTenant(request: APIRequestContext): Promise<Tenant> {
  const name = `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
  const project = await request.post('/api/v1/projects', { headers: admin, data: { name } })
  expect(project.status()).toBe(201)
  const projectId = ((await project.json()) as { id: string }).id
  const key = await request.post(`/api/v1/projects/${projectId}/api-keys`, { headers: admin, data: { label: 'e2e' } })
  expect(key.status()).toBe(201)
  return { projectId, key: ((await key.json()) as { secret: string }).secret }
}

export async function submitJob(request: APIRequestContext, tenant: Tenant, job: object): Promise<string> {
  const response = await request.post('/api/v1/jobs', {
    headers: { Authorization: `Bearer ${tenant.key}` },
    data: job,
  })
  expect(response.status()).toBe(201)
  return ((await response.json()) as { id: string }).id
}

/** Polls the API, not the page, so a slow fleet fails with the job's real status rather than a missing element. */
export async function waitForStatus(
  request: APIRequestContext,
  tenant: Tenant,
  jobId: string,
  status: string,
  timeout = 60_000,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const response = await request.get(`/api/v1/jobs/${jobId}`, {
          headers: { Authorization: `Bearer ${tenant.key}` },
        })
        return ((await response.json()) as { status: string }).status
      },
      { timeout, intervals: [250, 500, 1_000] },
    )
    .toBe(status)
}

export async function signIn(page: Page, credential: string): Promise<void> {
  await page.goto('/')
  await page.getByLabel('Token or API key').fill(credential)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible()
}

/** A console panel, found by its heading. */
export function panel(page: Page, title: string) {
  return page.locator('section.panel').filter({ has: page.getByRole('heading', { name: title, exact: true }) })
}

export async function navigate(page: Page, label: string): Promise<void> {
  await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: label, exact: true }).click()
}
