// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useAvailabilityGate } from './useAvailabilityGate'
import { checkHealth } from '../lib/api/health'
import { checkControl } from '../lib/api/control'
import { isWithinOpeningHours } from './schedule'

vi.mock('../lib/api/health', () => ({ checkHealth: vi.fn() }))
vi.mock('../lib/api/control', () => ({ checkControl: vi.fn() }))
vi.mock('../config/schedule', () => ({
  loadSchedule: () => ({
    tz: 'Europe/Berlin',
    days: ['mon', 'tue', 'wed', 'thu', 'fri'],
    fromMinutes: 480,
    toMinutes: 1320,
  }),
}))
vi.mock('./schedule', () => ({ isWithinOpeningHours: vi.fn() }))

const mockedCheckHealth = vi.mocked(checkHealth)
const mockedCheckControl = vi.mocked(checkControl)
const mockedIsWithin = vi.mocked(isWithinOpeningHours)

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

/** Flush pending microtasks and any React state updates. */
async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
}

/** Advance fake timers and flush the resulting microtasks. */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  mockedCheckHealth.mockReset()
  mockedCheckControl.mockReset()
  mockedIsWithin.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useAvailabilityGate', () => {
  it('loads healthy in-hours without any control call', async () => {
    mockedIsWithin.mockReturnValue(true)
    mockedCheckHealth.mockResolvedValue('healthy')

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()

    expect(mockedCheckHealth).toHaveBeenCalledTimes(1)
    expect(mockedCheckControl).not.toHaveBeenCalled()
    expect(result.current.phase).toBe('healthy')
    expect(result.current.isHealthy).toBe(true)
  })

  it('routes off-hours arrivals through control first without a health request', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValue({ state: 'UP' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()

    expect(mockedCheckHealth).not.toHaveBeenCalled()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)
    expect(result.current.phase).toBe('healthy')
  })

  it('keeps polling control every 3s without overlapping while STARTING', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValue({ state: 'STARTING' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)
    expect(result.current.phase).toBe('waking')

    await advance(3000)
    expect(mockedCheckControl).toHaveBeenCalledTimes(2)
    expect(mockedCheckHealth).not.toHaveBeenCalled()

    await advance(3000)
    expect(mockedCheckControl).toHaveBeenCalledTimes(3)
    expect(result.current.phase).toBe('waking')
  })

  it('does not overlap control calls while a request is still in flight', async () => {
    mockedIsWithin.mockReturnValue(false)
    const first = deferred<{ state: 'UP' | 'STARTING' }>()
    mockedCheckControl.mockReturnValueOnce(first.promise).mockResolvedValue({ state: 'STARTING' })

    renderHook(() => useAvailabilityGate())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)

    await advance(9000)
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)

    first.resolve({ state: 'STARTING' })
    await flush()
    await advance(3000)
    expect(mockedCheckControl).toHaveBeenCalledTimes(2)
  })

  it('treats a failed or invalid control response as not ready and keeps polling', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValue({ state: 'error' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)
    expect(result.current.phase).toBe('waking')

    await advance(3000)
    expect(mockedCheckControl).toHaveBeenCalledTimes(2)
    expect(result.current.phase).toBe('waking')
  })

  it('stops polling and opens immediately on a valid UP', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValueOnce({ state: 'STARTING' }).mockResolvedValue({ state: 'UP' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    expect(result.current.phase).toBe('waking')

    await advance(3000)
    expect(result.current.phase).toBe('healthy')
    expect(result.current.isHealthy).toBe(true)

    const calls = mockedCheckControl.mock.calls.length
    await advance(30_000)
    expect(mockedCheckControl.mock.calls.length).toBe(calls)
  })

  it('reaches STUCK after 180s without a valid UP and stops polling', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValue({ state: 'STARTING' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    expect(result.current.phase).toBe('waking')

    await advance(180_000)
    expect(result.current.phase).toBe('stuck')

    const calls = mockedCheckControl.mock.calls.length
    expect(calls).toBeGreaterThan(2)
    await advance(30_000)
    expect(mockedCheckControl.mock.calls.length).toBe(calls)
  })

  it('Try again restarts the cycle and resets the 180s window', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValue({ state: 'STARTING' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    await advance(180_000)
    expect(result.current.phase).toBe('stuck')
    const before = mockedCheckControl.mock.calls.length

    act(() => result.current.retry())
    await flush()
    expect(result.current.phase).toBe('waking')
    expect(mockedCheckControl.mock.calls.length).toBeGreaterThan(before)

    await advance(179_000)
    expect(result.current.phase).toBe('waking')
    await advance(1000)
    expect(result.current.phase).toBe('stuck')
  })

  it('ignores a stale control result from before a retry', async () => {
    mockedIsWithin.mockReturnValue(false)
    const stale = deferred<{ state: 'UP' | 'STARTING' }>()
    mockedCheckControl.mockReturnValueOnce(stale.promise).mockResolvedValue({ state: 'STARTING' })

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)
    expect(result.current.phase).toBe('waking')

    await advance(180_000)
    expect(result.current.phase).toBe('stuck')
    expect(mockedCheckControl.mock.calls[0][0]?.aborted).toBe(true)
    act(() => result.current.retry())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(2)

    stale.resolve({ state: 'UP' })
    await flush()
    expect(result.current.phase).toBe('waking')
  })

  it('shares an off-hours preflight and waits for the next tick after STARTING', async () => {
    mockedIsWithin.mockReturnValue(false)
    mockedCheckControl.mockResolvedValueOnce({ state: 'UP' }).mockResolvedValue({ state: 'STARTING' })
    const { result } = renderHook(() => useAvailabilityGate())
    await flush()

    await act(async () => {
      const decisions = await Promise.all([
        result.current.ensureReady(),
        result.current.ensureReady(),
        result.current.reportFailure(),
      ])
      expect(decisions.slice(0, 2)).toEqual([false, false])
    })
    expect(result.current.phase).toBe('waking')
    expect(mockedCheckControl).toHaveBeenCalledTimes(2)
    await advance(2999)
    expect(mockedCheckControl).toHaveBeenCalledTimes(2)
    await advance(1)
    expect(mockedCheckControl).toHaveBeenCalledTimes(3)
  })

  it('Start demo triggers an immediate control check and debounces rapid presses', async () => {
    const initial = deferred<'healthy'>()
    mockedIsWithin.mockReturnValue(true)
    mockedCheckHealth.mockReturnValue(initial.promise)

    const { result } = renderHook(() => useAvailabilityGate())
    await flush()
    expect(result.current.phase).toBe('checking')

    mockedCheckControl.mockResolvedValue({ state: 'STARTING' })
    act(() => result.current.startDemo())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)
    expect(result.current.phase).toBe('waking')
    expect(mockedCheckHealth.mock.calls[0][0]?.aborted).toBe(true)
    initial.resolve('healthy')
    await flush()
    expect(result.current.phase).toBe('waking')

    act(() => result.current.startDemo())
    act(() => result.current.startDemo())
    await flush()
    expect(mockedCheckControl).toHaveBeenCalledTimes(1)
  })
})
