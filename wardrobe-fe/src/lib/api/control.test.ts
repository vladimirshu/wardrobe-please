// @vitest-environment node
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { checkControl } from './control'

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// In the Node test environment undici needs an absolute URL; provide an ambient origin.
;(globalThis as { location?: { origin: string } }).location = { origin: 'http://localhost' }

describe('checkControl', () => {
  it('parses a valid UP response', async () => {
    server.use(http.get('*/api/v1/control/up', () => HttpResponse.json({ state: 'UP' })))
    expect(await checkControl()).toEqual({ state: 'UP' })
  })

  it('parses a valid STARTING response', async () => {
    server.use(http.get('*/api/v1/control/up', () => HttpResponse.json({ state: 'STARTING' })))
    expect(await checkControl()).toEqual({ state: 'STARTING' })
  })

  it('treats a 200 body without a valid state as an error', async () => {
    server.use(http.get('*/api/v1/control/up', () => HttpResponse.json({ state: 'SLEEPING' })))
    expect(await checkControl()).toEqual({ state: 'error' })
  })

  it('treats a 200 with an empty body as an error', async () => {
    server.use(http.get('*/api/v1/control/up', () => HttpResponse.json({})))
    expect(await checkControl()).toEqual({ state: 'error' })
  })

  it('treats a 200 with a non-JSON body as an error', async () => {
    server.use(
      http.get('*/api/v1/control/up', () => new HttpResponse('UP', { status: 200 })),
    )
    expect(await checkControl()).toEqual({ state: 'error' })
  })

  it('treats an HTTP error as an error even if the body looks valid', async () => {
    server.use(http.get('*/api/v1/control/up', () => HttpResponse.json({ state: 'UP' }, { status: 500 })))
    expect(await checkControl()).toEqual({ state: 'error' })
  })

  it('treats a network failure as an error', async () => {
    server.use(http.get('*/api/v1/control/up', () => HttpResponse.error()))
    expect(await checkControl()).toEqual({ state: 'error' })
  })

  it('aborts the request after the 5s timeout', async () => {
    vi.useFakeTimers()
    try {
      server.use(http.get('*/api/v1/control/up', () => new Promise(() => {})))
      const result = checkControl()
      await vi.advanceTimersByTimeAsync(5100)
      await expect(result).resolves.toEqual({ state: 'error' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('honours an external abort signal', async () => {
    server.use(http.get('*/api/v1/control/up', () => new Promise(() => {})))
    const controller = new AbortController()
    controller.abort()
    expect(await checkControl(controller.signal)).toEqual({ state: 'error' })
  })
})
