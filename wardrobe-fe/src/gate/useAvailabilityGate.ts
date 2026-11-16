import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { checkHealth } from '../lib/api/health'
import { checkControl } from '../lib/api/control'
import { isWithinOpeningHours } from './schedule'
import { loadSchedule } from '../config/schedule'
import { readGatePreviewOverride } from './devPreview'

export type GatePhase = 'checking' | 'closed' | 'waking' | 'healthy' | 'stuck'

const CONTROL_POLL_MS = 3000
const STUCK_AFTER_MS = 180_000
const START_DEBOUNCE_MS = 1000

type ProbeKind = 'health' | 'control'

interface Probe {
  kind: ProbeKind
  controller: AbortController
  startedAt: number
  result: Promise<boolean>
}

export interface AvailabilityGate {
  phase: GatePhase
  isHealthy: boolean
  /** Re-evaluate readiness before a backend operation. Returns true when the request may proceed. */
  ensureReady: () => Promise<boolean>
  /** Report a network/5xx request failure so the gate can re-check and possibly re-open. */
  reportFailure: () => Promise<void>
  /** Manual immediate control check (debounced). */
  startDemo: () => void
  /** Restart the wake cycle from a stuck state. */
  retry: () => void
  /** True when a control call or startup polling is currently active. */
  busy: boolean
}

export function useAvailabilityGate(): AvailabilityGate {
  const [phase, setPhase] = useState<GatePhase>('checking')
  const phaseRef = useRef<GatePhase>('checking')
  const probeRef = useRef<Probe | null>(null)
  const lastStartRef = useRef(-Infinity)
  const stopWakeRef = useRef<(() => void) | null>(null)
  const schedule = useMemo(() => loadSchedule(), [])
  const devOverride = useMemo(() => (import.meta.env.DEV ? readGatePreviewOverride() : null), [])

  const setGatePhase = useCallback((next: GatePhase) => {
    phaseRef.current = next
    setPhase(next)
  }, [])

  const probe = useCallback((kind: ProbeKind): Probe => {
    const current = probeRef.current
    if (current?.kind === kind) return current
    current?.controller.abort()

    const controller = new AbortController()
    const startedAt = Date.now()
    const result = kind === 'health'
      ? checkHealth(controller.signal).then((health) => health === 'healthy')
      : checkControl(controller.signal).then((control) => control.state === 'UP')
    const request = { kind, controller, startedAt, result }
    probeRef.current = request
    void result.then(() => {
      if (probeRef.current === request) probeRef.current = null
    })
    return request
  }, [])

  const beginWake = useCallback((firstCheckAt?: number) => {
    if (stopWakeRef.current) return
    setGatePhase('waking')
    const startedAt = firstCheckAt ?? Date.now()
    const deadline = startedAt + STUCK_AFTER_MS
    let polling = false

    const stop = () => {
      window.clearInterval(interval)
      window.clearTimeout(timeout)
      stopWakeRef.current = null
    }

    const expire = () => {
      stop()
      probeRef.current?.controller.abort()
      probeRef.current = null
      setGatePhase('stuck')
    }

    const poll = async () => {
      if (polling) return
      if (Date.now() >= deadline) {
        expire()
        return
      }
      polling = true
      const request = probe('control')
      const ready = await request.result
      polling = false
      if (stopWakeRef.current !== stop) return
      if (Date.now() >= deadline) {
        expire()
      } else if (ready) {
        stop()
        setGatePhase('healthy')
      }
    }

    const timeout = window.setTimeout(expire, deadline - Date.now())
    const nextPollIn = Math.max(0, startedAt + CONTROL_POLL_MS - Date.now())
    let interval = window.setTimeout(() => {
      interval = window.setInterval(() => void poll(), CONTROL_POLL_MS)
      void poll()
    }, nextPollIn)
    stopWakeRef.current = stop
    if (firstCheckAt === undefined) void poll()
  }, [probe, setGatePhase])

  useEffect(() => {
    const lifecycle = new AbortController()
    // Wait for the effect to survive StrictMode's setup/cleanup replay.
    void Promise.resolve().then(async () => {
      if (lifecycle.signal.aborted) return
      if (devOverride) {
        setGatePhase(devOverride)
      } else if (isWithinOpeningHours(new Date(), schedule)) {
        const request = probe('health')
        const ready = await request.result
        if (lifecycle.signal.aborted || request.controller.signal.aborted) return
        if (ready) setGatePhase('healthy')
        else beginWake()
      } else {
        beginWake()
      }
    })
    return () => {
      lifecycle.abort()
      stopWakeRef.current?.()
      probeRef.current?.controller.abort()
      probeRef.current = null
    }
  }, [beginWake, devOverride, probe, schedule, setGatePhase])

  const recheck = useCallback(async (request: Probe): Promise<boolean> => {
    const ready = await request.result
    if (request.controller.signal.aborted || phaseRef.current !== 'healthy') return false
    if (!ready) beginWake(request.kind === 'control' ? request.startedAt : undefined)
    return ready
  }, [beginWake])

  const ensureReady = useCallback(async (): Promise<boolean> => {
    if (phaseRef.current !== 'healthy') return false
    if (!isWithinOpeningHours(new Date(), schedule)) return recheck(probe('control'))
    const pending = probeRef.current
    return pending ? recheck(pending) : true
  }, [probe, recheck, schedule])

  const reportFailure = useCallback(async (): Promise<void> => {
    if (phaseRef.current !== 'healthy') return
    const kind = isWithinOpeningHours(new Date(), schedule) ? 'health' : 'control'
    await recheck(probeRef.current?.kind === 'control' ? probeRef.current : probe(kind))
  }, [probe, recheck, schedule])

  const startDemo = useCallback(() => {
    const now = Date.now()
    if (stopWakeRef.current || now - lastStartRef.current < START_DEBOUNCE_MS) return
    lastStartRef.current = now
    beginWake()
  }, [beginWake])

  const retry = useCallback(() => {
    if (phaseRef.current === 'stuck') beginWake()
  }, [beginWake])

  return useMemo<AvailabilityGate>(
    () => ({
      phase,
      isHealthy: phase === 'healthy',
      ensureReady,
      reportFailure,
      startDemo,
      retry,
      busy: phase === 'waking',
    }),
    [phase, ensureReady, reportFailure, startDemo, retry],
  )
}
