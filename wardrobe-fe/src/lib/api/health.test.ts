// @vitest-environment node
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { checkHealth } from './health'

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// In the Node test environment undici needs an absolute URL; provide an ambient origin.
;(globalThis as { location?: { origin: string } }).location = { origin: 'http://localhost' }

describe('checkHealth', () => {
  it('treats 2xx as healthy', async () => {
    server.use(http.get('*/api/v1/health', () => HttpResponse.json({ status: 'UP' })))
    expect(await checkHealth()).toBe('healthy')
  })

  it('treats 429 as healthy', async () => {
    server.use(
      http.get('*/api/v1/health', () => HttpResponse.json({ message: 'slow down' }, { status: 429 })),
    )
    expect(await checkHealth()).toBe('healthy')
  })

  it('treats 5xx as unhealthy', async () => {
    server.use(http.get('*/api/v1/health', () => HttpResponse.json({ error: 'boom' }, { status: 503 })))
    expect(await checkHealth()).toBe('unhealthy')
  })

  it('treats other 4xx as unhealthy', async () => {
    server.use(http.get('*/api/v1/health', () => HttpResponse.json({ error: 'denied' }, { status: 403 })))
    expect(await checkHealth()).toBe('unhealthy')
  })

  it('treats a network failure as unhealthy', async () => {
    server.use(http.get('*/api/v1/health', () => HttpResponse.error()))
    expect(await checkHealth()).toBe('unhealthy')
  })

  it('treats any 2xx as healthy regardless of the body', async () => {
    server.use(
      http.get('*/api/v1/health', () => new HttpResponse('not json', { status: 200 })),
    )
    expect(await checkHealth()).toBe('healthy')
  })

  it('aborts the request after the 2.5s timeout', async () => {
    vi.useFakeTimers()
    try {
      server.use(http.get('*/api/v1/health', () => new Promise(() => {})))
      const result = checkHealth()
      await vi.advanceTimersByTimeAsync(2600)
      await expect(result).resolves.toBe('unhealthy')
    } finally {
      vi.useRealTimers()
    }
  })

  it('honours an external abort signal', async () => {
    server.use(
      http.get('*/api/v1/health', () => new Promise(() => {})),
    )
    const controller = new AbortController()
    const result = checkHealth(controller.signal)
    controller.abort()
    await expect(result).resolves.toBe('unhealthy')
  })

  it('returns unhealthy immediately for an already-aborted signal', async () => {
    const controller = new AbortController()
    controller.abort()
    expect(await checkHealth(controller.signal)).toBe('unhealthy')
  })
})
