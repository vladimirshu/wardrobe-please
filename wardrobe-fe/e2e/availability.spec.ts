import { test, expect } from '@playwright/test'
import axe from 'axe-core'

// Availability-gate specific flows. These run against the production build
// (`vite preview`), so dev-only fixtures and preview overrides are absent.
// A fixed Sunday time (2026-11-08) is used to force the off-hours control-first
// routing deterministically regardless of the real clock.

const OFF_HOURS = new Date('2026-11-08T01:00:00Z')

test('production build cannot bypass readiness with the dev gate override', async ({ page }) => {
  await page.clock.setFixedTime(OFF_HOURS)
  await page.route('**/api/v1/health', (route) =>
    route.fulfill({ status: 503, contentType: 'application/json', body: '{"status":"DOWN"}' }),
  )
  await page.route('**/api/v1/control/up', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"state":"STARTING"}' }),
  )
  // `?gate=healthy` is a development-only override; it must not exist here.
  await page.goto('/?gate=healthy')
  await expect(page.getByRole('region', { name: 'Service availability' })).toBeVisible()
})

test('an invalid control body keeps the gate closed and keeps polling', async ({ page }) => {
  await page.clock.install({ time: OFF_HOURS })
  let controlCalls = 0
  await page.route('**/api/v1/control/up', (route) => {
    controlCalls++
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"state":"SLEEPING"}' })
  })
  await page.goto('/')
  await expect(page.getByRole('region', { name: 'Service availability' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Getting the shop ready.' })).toBeVisible()
  await expect.poll(() => controlCalls).toBeGreaterThanOrEqual(1)

  await page.clock.runFor(3000)
  await expect.poll(() => controlCalls).toBe(2)
})

test('a startup that never completes reaches stuck and Try again resumes', async ({ page }) => {
  await page.clock.install({ time: OFF_HOURS })
  let ready = false
  await page.route('**/api/v1/control/up', (route) =>
    route.fulfill({ json: { state: ready ? 'UP' : 'STARTING' } }),
  )
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Getting the shop ready.' })).toBeVisible()

  await page.clock.runFor(180_000)
  await expect(page.getByRole('heading', { name: "The demo couldn't start." })).toBeVisible()
  const retry = page.getByRole('button', { name: 'Try again' })
  await expect(retry).toBeVisible()

  await retry.click()
  await expect(page.getByRole('heading', { name: 'Getting the shop ready.' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Starting…' })).toBeFocused()

  ready = true
  await page.clock.runFor(3000)
  await expect(page.getByPlaceholder('What are you looking for?')).toBeFocused()
})

test('the gate sends no heartbeat after opening', async ({ page }) => {
  await page.clock.install({ time: OFF_HOURS })
  let controlCalls = 0
  let healthCalls = 0
  await page.route('**/api/v1/control/up', (route) => {
    controlCalls++
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"state":"UP"}' })
  })
  await page.route('**/api/v1/health', (route) => {
    healthCalls++
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"UP"}' })
  })
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Find my match' })).toBeEnabled()
  await expect(page.getByRole('region', { name: 'Service availability' })).toHaveCount(0)

  await page.clock.runFor(60_000)
  expect(controlCalls).toBe(1)
  expect(healthCalls).toBe(0)
})

test('the campaign tag is preserved across an off-hours wake', async ({ page }) => {
  await page.clock.install({ time: OFF_HOURS })
  await page.route('**/api/v1/control/up', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"state":"UP"}' }),
  )
  await page.goto('/?ref=acme')
  await expect(page.getByRole('button', { name: 'Find my match' })).toBeEnabled()
  await expect(page.getByRole('region', { name: 'Service availability' })).toHaveCount(0)
  await expect(page).toHaveURL(/\?ref=acme/)
})

test('a search submitted after closing time checks control before sending', async ({ page }) => {
  // Friday 2026-10-30 12:00 UTC is 13:00 Berlin (CET), inside the opening window.
  await page.clock.install({ time: new Date('2026-10-30T12:00:00Z') })
  let controlCalls = 0
  let ready = false
  await page.route('**/api/v1/control/up', (route) => {
    controlCalls++
    return route.fulfill({ json: { state: ready ? 'UP' : 'STARTING' } })
  })
  let healthCalls = 0
  await page.route('**/api/v1/health', (route) => {
    healthCalls++
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"UP"}' })
  })
  let searchCalls = 0
  await page.route('**/api/v1/search*', (route) => {
    searchCalls++
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        products: [],
        interpretation: { searchQuery: 'warm jacket', negativeExamples: [], priceConstraint: null, sortByLowestPrice: false },
        suggestedPrompts: null,
      }),
    })
  })

  await page.goto('/')
  // In-hours and healthy: no panel, no control call.
  const submit = page.getByRole('button', { name: 'Find my match' })
  await expect(submit).toBeEnabled()
  await expect(page.getByRole('region', { name: 'Service availability' })).toHaveCount(0)
  expect(controlCalls).toBe(0)
  expect(healthCalls).toBe(1)

  // Cross the closing instant (22:00 Berlin = 21:00 UTC) and submit.
  await page.clock.fastForward(9.5 * 60 * 60 * 1000)
  const field = page.getByPlaceholder('What are you looking for?')
  await field.fill('warm jacket')
  await submit.click()

  await expect(page.getByRole('region', { name: 'Service availability' })).toBeVisible()
  // Off-hours submission checks control (not health) and blocks the search.
  expect(controlCalls).toBe(1)
  expect(healthCalls).toBe(1)
  expect(searchCalls).toBe(0)
  await expect(field).toHaveValue('warm jacket')
  await expect(submit).toBeDisabled()

  ready = true
  await page.clock.runFor(3000)
  await expect(submit).toBeEnabled()
  expect(searchCalls).toBe(0)
  await submit.click()
  await expect.poll(() => searchCalls).toBe(1)
  expect(controlCalls).toBe(3)
})

test('an in-hours health probe keeps the panel hidden while pending', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-09T12:00:00Z'))
  let allowHealth!: () => void
  const pending = new Promise<void>((resolve) => { allowHealth = resolve })
  await page.route('**/api/v1/health', async (route) => {
    await pending
    await route.fulfill({ json: { status: 'UP' } })
  })
  await page.goto('/')
  const submit = page.getByRole('button', { name: 'Find my match' })
  await expect(submit).toBeDisabled()
  await expect(page.getByRole('region', { name: 'Service availability' })).toHaveCount(0)
  allowHealth()
  await expect(submit).toBeEnabled()
})

test('static content stays keyboard-accessible while the panel waits', async ({ page }) => {
  await page.clock.setFixedTime(OFF_HOURS)
  await page.route('**/api/v1/health', (route) =>
    route.fulfill({ status: 503, contentType: 'application/json', body: '{"status":"DOWN"}' }),
  )
  await page.route('**/api/v1/control/up', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"state":"STARTING"}' }),
  )
  await page.goto('/')
  const panel = page.getByRole('region', { name: 'Service availability' })
  await expect(panel).toBeVisible()

  // The non-modal panel does not trap focus or make the static page inert.
  const men = page.getByRole('link', { name: 'Men', exact: true })
  const field = page.getByPlaceholder('What are you looking for?')
  const demo = page.getByLabel('Example search demonstration')
  await expect(men).toBeEnabled()
  await expect(field).toBeEnabled()
  await expect(demo.getByRole('button', { name: 'Pause' })).toBeEnabled()

  // Focus flows through the static content, not into a trap.
  await field.focus()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: 'Find my match' })).toBeFocused()
})

for (const width of [390, 1440]) {
  test(`startup panel fits at ${width}px and has no serious accessibility violations`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.clock.setFixedTime(OFF_HOURS)
    await page.route('**/api/v1/control/up', (route) => route.fulfill({ json: { state: 'STARTING' } }))
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Getting the shop ready.' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.addScriptTag({ content: axe.source })
    const serious = await page.evaluate(async () => {
      const results = await (window as unknown as { axe: typeof axe }).axe.run()
      return results.violations.filter(({ impact }) => impact === 'critical' || impact === 'serious')
    })
    expect(serious).toEqual([])
  })
}

