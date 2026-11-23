// @vitest-environment node
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { searchProducts } from './search'
import type { Product, Interpretation } from './types'

const product: Product = {
  sku: 'JKT0003',
  title: 'Sage Puffer Jacket',
  category: 'jackets',
  colors: ['Sage'],
  sizes: ['M'],
  price: 189,
  description: 'A quilted puffer.',
  imageUrl: '/images/categories/puffer.webp',
}

const interpretation: Interpretation = {
  searchQuery: 'rain jacket',
  negativeExamples: [],
  priceConstraint: null,
  sortByLowestPrice: false,
}

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// In the Node test environment undici needs an absolute URL; provide an ambient origin.
;(globalThis as { location?: { origin: string } }).location = { origin: 'http://localhost' }

const req = { message: 'rain jacket', conversationId: 'abc', clearMemory: true }

describe('searchProducts', () => {
  it('sends search fields only as JSON to the bare search path', async () => {
    let captured: Request | undefined
    server.use(http.post('*/api/v1/search', ({ request }) => {
      captured = request.clone()
      return HttpResponse.json({ products: [product], interpretation, suggestedPrompts: null })
    }))
    await searchProducts(req)
    expect(captured?.method).toBe('POST')
    expect(new URL(captured!.url).pathname).toBe('/api/v1/search')
    expect(new URL(captured!.url).search).toBe('')
    expect(captured?.headers.get('Content-Type')).toBe('application/json')
    expect(await captured?.json()).toEqual(req)
  })

  it('parses a 200 success body', async () => {
    server.use(
      http.post('*/api/v1/search', () =>
        HttpResponse.json({ products: [product], interpretation, suggestedPrompts: null }),
      ),
    )
    const result = await searchProducts(req)
    expect(result).toEqual({ kind: 'success', products: [product], interpretation })
  })

  it('parses a 422 rejection with suggestions', async () => {
    server.use(
      http.post('*/api/v1/search', () =>
        HttpResponse.json(
          { products: null, interpretation: null, suggestedPrompts: ['A warm winter jacket'] },
          { status: 422 },
        ),
      ),
    )
    const result = await searchProducts(req)
    expect(result).toEqual({ kind: 'rejected', suggestedPrompts: ['A warm winter jacket'] })
  })

  it('parses a 400 field error', async () => {
    server.use(
      http.post('*/api/v1/search', () =>
        HttpResponse.json({ message: 'Message contains unsupported characters' }, { status: 400 }),
      ),
    )
    const result = await searchProducts(req)
    expect(result).toEqual({
      kind: 'fieldError',
      errors: { message: 'Message contains unsupported characters' },
    })
  })

  it('parses a 429 rate-limit with Retry-After', async () => {
    server.use(
      http.post('*/api/v1/search', () =>
        HttpResponse.json({ code: 'RATE_LIMITED', message: 'slow down' }, {
          status: 429,
          headers: { 'Retry-After': '30' },
        }),
      ),
    )
    const result = await searchProducts(req)
    expect(result).toEqual({
      kind: 'rateLimited',
      code: 'RATE_LIMITED',
      message: 'slow down',
      retryAfterSeconds: 30,
    })
  })

  it('parses a 429 daily-budget-exhausted without retry', async () => {
    server.use(
      http.post('*/api/v1/search', () =>
        HttpResponse.json({ code: 'DAILY_BUDGET_EXHAUSTED', message: 'quota' }, { status: 429 }),
      ),
    )
    const result = await searchProducts(req)
    expect(result).toEqual({
      kind: 'rateLimited',
      code: 'DAILY_BUDGET_EXHAUSTED',
      message: 'quota',
      retryAfterSeconds: null,
    })
  })

  it.each([500, 504])('maps HTTP %s to serverError', async (status) => {
    server.use(
      http.post('*/api/v1/search', () => HttpResponse.json({ error: 'boom' }, { status })),
    )
    expect(await searchProducts(req)).toEqual({ kind: 'serverError' })
  })

  it('aborts a hung search at 30 seconds without replaying it', async () => {
    vi.useFakeTimers()
    let signal!: AbortSignal
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation((_url, init) => {
      signal = init!.signal as AbortSignal
      return new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(signal.reason), { once: true })
      })
    })
    try {
      const pending = searchProducts(req)
      await vi.advanceTimersByTimeAsync(29_999)
      expect(signal.aborted).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(await pending).toEqual({ kind: 'network' })
      expect(signal.aborted).toBe(true)
      expect(fetch).toHaveBeenCalledTimes(1)
    } finally {
      fetch.mockRestore()
      vi.useRealTimers()
    }
  })

  it('maps a network failure to network', async () => {
    server.use(http.post('*/api/v1/search', () => HttpResponse.error()))
    expect(await searchProducts(req)).toEqual({ kind: 'network' })
  })

  it('maps an aborted request to aborted', async () => {
    server.use(
      http.post('*/api/v1/search', () => HttpResponse.json({ products: [product], interpretation, suggestedPrompts: null })),
    )
    const controller = new AbortController()
    controller.abort()
    expect(await searchProducts(req, controller.signal)).toEqual({ kind: 'aborted' })
  })
})
