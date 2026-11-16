import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useGate } from './GateContext'
import { openingHoursLabel } from './schedule'
import { loadSchedule } from '../config/schedule'
import { useReducedMotion } from '../lib/useReducedMotion'
import {
  RESTING_MESSAGE,
  RESTING_TITLE,
  STUCK_MESSAGE,
  STUCK_TITLE,
  WAKING_MESSAGES,
  WAKING_TITLE,
} from './messages'

function formatElapsed(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

/**
 * When the panel disappears, move focus from a removed panel action back to the
 * search action rather than losing it to the document body.
 */
function restoreFocusFromPanel(container: HTMLElement | null): void {
  const active = document.activeElement
  if (!(active instanceof HTMLElement) || !container?.contains(active)) return
  const searchInput = document.querySelector<HTMLElement>('#search-input, #follow-up-input:not([hidden] *)')
  if (searchInput) {
    searchInput.focus()
    return
  }
  const fallback = document.querySelector<HTMLElement>('main a[href], main button')
  fallback?.focus()
}

export function AvailabilityPanel() {
  const gate = useGate()
  const reducedMotion = useReducedMotion()

  const [elapsed, setElapsed] = useState(0)
  const sectionRef = useRef<HTMLElement | null>(null)

  const hours = useMemo(() => openingHoursLabel(loadSchedule()), [])

  const isStarting = gate.phase === 'waking'
  const isStuck = gate.phase === 'stuck'

  // Return focus to the search action when the panel is removed while one of
  // its actions still has focus. Runs before the DOM is detached.
  useLayoutEffect(() => {
    const node = sectionRef.current
    return () => restoreFocusFromPanel(node)
  }, [])

  // Derive elapsed time from the clock so throttled browser timers catch up.
  useEffect(() => {
    if (!isStarting) return
    const start = Date.now()
    setElapsed(0)
    const id = window.setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000)
    return () => window.clearInterval(id)
  }, [isStarting])

  const title = isStuck ? STUCK_TITLE : isStarting ? WAKING_TITLE : RESTING_TITLE
  const messageIndex = reducedMotion ? 0 : Math.floor(elapsed / 4) % WAKING_MESSAGES.length
  const body = isStuck ? STUCK_MESSAGE : isStarting ? WAKING_MESSAGES[messageIndex] : RESTING_MESSAGE
  const announcementStep = Math.floor(elapsed / 12)
  const announcement = isStarting && !reducedMotion && announcementStep > 0
    ? WAKING_MESSAGES[(announcementStep - 1) % WAKING_MESSAGES.length]
    : title
  const starting = isStarting || gate.busy

  return (
    <section
      ref={sectionRef}
      aria-label="Service availability"
      className="rounded-sm border border-border bg-wash/50 px-5 py-4"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="font-serif text-lg text-ink">{title}</h2>
          <p className="mt-1 text-sm text-ink">{body}</p>
          {hours && (
            <p className="mt-2 text-xs text-ink">
              Opening hours: {hours.days} {hours.from}–{hours.to}
              {hours.endsNextDay ? ' the next day' : ''} (your time)
            </p>
          )}
          {(isStarting || isStuck) && (
            <p className="mt-1 text-xs tabular-nums text-ink" aria-hidden="true">
              {formatElapsed(elapsed)}
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={starting ? undefined : isStuck ? gate.retry : gate.startDemo}
          aria-disabled={starting}
          className="shrink-0 rounded-sm border border-action bg-action px-4 py-2 text-sm text-paper hover:bg-action/90 focus:outline focus:outline-2 focus:outline-offset-2 focus:outline-action aria-disabled:cursor-not-allowed aria-disabled:border-border aria-disabled:bg-transparent aria-disabled:text-ink"
        >
          {starting ? 'Starting…' : isStuck ? 'Try again' : 'Start demo'}
        </button>
      </div>
      <span className="sr-only" role="status" aria-live="polite">
        {announcement}
      </span>
    </section>
  )
}
