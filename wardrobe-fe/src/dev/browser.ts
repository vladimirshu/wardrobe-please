import { setupWorker } from 'msw/browser'
import { devAvailabilityHandlers } from './fixtures'

/**
 * Start the development-only MSW worker for the availability endpoints. Only
 * imported from `main.tsx` under `import.meta.env.DEV`, so it is absent from
 * production builds.
 */
export async function startDevFixtures(): Promise<void> {
  const worker = setupWorker(...devAvailabilityHandlers)
  await worker.start({
    onUnhandledRequest: 'bypass',
    quiet: true,
  })
}
