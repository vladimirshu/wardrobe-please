import { http, HttpResponse } from 'msw'

/**
 * Development-only health/control fixtures (MSW). When the browser worker is
 * started (see `src/dev/browser.ts`, only under `import.meta.env.DEV`) these
 * handlers answer the availability endpoints so the gate can be exercised
 * without the real backend or control plane.
 *
 * Query parameters drive the behaviour:
 * - `?health=fail`  → the health probe answers 503 (unhealthy).
 * - `?control=starting` → `/api/v1/control/up` always answers STARTING.
 * - `?control=error`    → `/api/v1/control/up` answers 500.
 * With no parameters the fixtures are "always available": health UP, control UP.
 */

function queryParam(name: string): string | null {
  return new URLSearchParams(window.location.search).get(name)
}

export const devAvailabilityHandlers = [
  http.get('/api/v1/health', () => {
    if (queryParam('health') === 'fail') {
      return HttpResponse.json({ status: 'DOWN' }, { status: 503 })
    }
    return HttpResponse.json({ status: 'UP' })
  }),

  http.get('/api/v1/control/up', () => {
    const mode = queryParam('control')
    if (mode === 'starting') return HttpResponse.json({ state: 'STARTING' })
    if (mode === 'error') return HttpResponse.json({ error: 'boom' }, { status: 500 })
    return HttpResponse.json({ state: 'UP' })
  }),
]
