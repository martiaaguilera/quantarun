import { expect, test } from '@playwright/test'
import { ADMIN_TOKEN, createTenant, navigate, panel, signIn, submitJob, waitForStatus } from './stack'

test('a wrong credential is refused and the operator token signs in', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Token or API key').fill('not-a-real-token-0000000000000000000000')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Primary' })).toHaveCount(0)

  await page.getByLabel('Token or API key').fill(ADMIN_TOKEN)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Overview', level: 1 })).toBeVisible()
  await expect(page.locator('.shell__identity')).toHaveText('Operator')
  await expect(page.locator('.stream')).toHaveText('Live')
})

test('the workers page shows the three heterogeneous workers with their capacity', async ({ page }) => {
  await signIn(page, ADMIN_TOKEN)
  await navigate(page, 'Workers')
  await expect(page.getByRole('heading', { name: 'Workers', level: 1 })).toBeVisible()
  for (const name of ['worker-cpu', 'worker-mixed', 'worker-accel']) {
    const row = page.getByRole('row').filter({ has: page.getByText(name, { exact: true }) })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('Active')
  }
  await expect(page.getByRole('row').filter({ hasText: 'worker-accel' })).toContainText('cuda, large-model')
})

test('a submitted job is followed from the queue to success, with the scheduler explaining its choice', async ({
  page,
  request,
}) => {
  const tenant = await createTenant(request)
  const jobId = await submitJob(request, tenant, {
    workloadType: 'staged',
    payload: { stages: [{ name: 'tokenize', durationMs: 300 }, { name: 'embed', durationMs: 300 }] },
    resources: { cpuMillis: 500, memoryMib: 256 },
    requiredLabels: ['cuda'],
  })

  // A project key sees only its own project; the job list is where a tenant starts.
  await signIn(page, tenant.key)
  await expect(page.locator('.shell__identity')).toContainText('Project')
  await navigate(page, 'Jobs')
  await page.locator(`a[href="/jobs/${jobId}"]`).first().click()

  await expect(page.getByRole('heading', { level: 1 })).toContainText(jobId)
  await waitForStatus(request, tenant, jobId, 'SUCCEEDED')
  await expect(page.locator('.page-header__facts')).toContainText('Succeeded')

  // Both stages committed a checkpoint, and the decision names a worker with the required label.
  await expect(panel(page, 'Checkpoints').getByRole('row')).toHaveCount(3)
  const decision = panel(page, 'Latest scheduling decision')
  await expect(decision).toContainText('Placed')
  await expect(decision).toContainText(/worker-(mixed|accel)/)
  await expect(decision).toContainText('missing labels [cuda]')
  await expect(panel(page, 'What happened')).toContainText('Succeeded')
})

test('a dead job is revived from the console and succeeds on its fresh budget', async ({ page, request }) => {
  const tenant = await createTenant(request)
  // Fails transiently on attempt 1, which is its whole budget; after a revive, attempt 2 succeeds.
  const jobId = await submitJob(request, tenant, {
    workloadType: 'fail',
    payload: { failureClass: 'TRANSIENT', message: 'upstream reset the connection', succeedOnAttempt: 2 },
    maxAttempts: 1,
    resources: { cpuMillis: 100, memoryMib: 64 },
  })
  await waitForStatus(request, tenant, jobId, 'DEAD')

  await signIn(page, tenant.key)
  await navigate(page, 'Dead jobs')
  const row = page.getByRole('row').filter({ has: page.locator(`a[href="/jobs/${jobId}"]`) })
  await expect(row).toHaveCount(1)
  await row.getByRole('button', { name: 'Revive' }).click()

  await waitForStatus(request, tenant, jobId, 'SUCCEEDED')
  await page.goto(`/jobs/${jobId}`)
  await expect(page.locator('.page-header__facts')).toContainText('Succeeded')
  await expect(page.locator('.page-header__facts')).toContainText('revived 1 times')
  await expect(panel(page, 'Attempts').getByRole('row')).toHaveCount(3)
})

test('the policy lab replays a scenario under two policies and reports the same hash for the same seed', async ({
  page,
}) => {
  await signIn(page, ADMIN_TOKEN)
  await navigate(page, 'Policy lab')
  const form = panel(page, 'Run a comparison')
  await form.getByLabel('Scenario').selectOption('NOISY_NEIGHBOR')
  await form.getByLabel('Seed').fill('42')
  await form.getByLabel('Jobs').fill('500')
  for (const policy of ['FIFO', 'PRIORITY', 'LEAST_LOADED', 'BIN_PACKING', 'FAIR_SHARE', 'DEADLINE']) {
    await form.getByRole('checkbox', { name: policy, exact: true }).setChecked(policy === 'FIFO' || policy === 'FAIR_SHARE')
  }

  // The hashes come from the API's answer to the console's own request; the page must show exactly those.
  const runOnce = async () => {
    const [response] = await Promise.all([
      page.waitForResponse(
        (candidate) => candidate.url().endsWith('/api/v1/simulations') && candidate.request().method() === 'POST',
        { timeout: 60_000 },
      ),
      form.getByRole('button', { name: 'Run comparison' }).click(),
    ])
    expect(response.status()).toBe(201)
    const run = (await response.json()) as { results: { policies: { policy: string; resultHash: string }[] } }
    const table = panel(page, 'Results').locator('table').filter({ hasText: 'Result hash' })
    for (const result of run.results.policies) {
      await expect(table.getByRole('row').filter({ hasText: result.policy }).locator('code')).toHaveAttribute(
        'title',
        result.resultHash,
      )
    }
    return run.results.policies.map((result) => [result.policy, result.resultHash])
  }

  const first = await runOnce()
  expect(first.map(([policy]) => policy)).toEqual(['FIFO', 'FAIR_SHARE'])
  expect(await runOnce()).toEqual(first)
})
