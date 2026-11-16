import { afterEach, expect, it, vi } from 'vitest'
import { loadSchedule } from './schedule'

afterEach(() => vi.unstubAllEnvs())

it('uses the application schedule even when legacy environment variables are set', () => {
  vi.stubEnv('VITE_OPEN_TZ', 'UTC')
  vi.stubEnv('VITE_OPEN_DAYS', 'sun')
  vi.stubEnv('VITE_OPEN_FROM', '01:00')
  vi.stubEnv('VITE_OPEN_TO', '02:00')

  expect(loadSchedule()).toEqual({
    tz: 'Europe/Berlin',
    days: ['mon', 'tue', 'wed', 'thu', 'fri'],
    fromMinutes: 480,
    toMinutes: 1320,
  })
})
