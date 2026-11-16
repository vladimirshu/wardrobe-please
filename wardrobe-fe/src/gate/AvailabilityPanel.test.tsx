// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { AvailabilityGateContext } from './GateContext'
import type { AvailabilityGate } from './useAvailabilityGate'
import { AvailabilityPanel } from './AvailabilityPanel'
import { RESTING_TITLE, STUCK_TITLE, WAKING_MESSAGES, WAKING_TITLE } from './messages'

function makeGate(phase: AvailabilityGate['phase'], busy = false): AvailabilityGate {
  return {
    phase,
    isHealthy: phase === 'healthy',
    ensureReady: async () => phase === 'healthy',
    reportFailure: async () => {},
    startDemo: vi.fn(),
    retry: vi.fn(),
    busy,
  }
}

function renderPanel(gate: AvailabilityGate) {
  return render(
    <AvailabilityGateContext.Provider value={gate}>
      <input id="search-input" aria-label="Describe the clothing you are looking for" />
      <AvailabilityPanel />
    </AvailabilityGateContext.Provider>,
  )
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('AvailabilityPanel', () => {
  it('shows the resting copy and Start demo while checking', () => {
    const gate = makeGate('checking')
    renderPanel(gate)
    expect(screen.getByRole('heading', { name: RESTING_TITLE })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start demo' })).toBeInTheDocument()
  })

  it('shows the starting copy and a disabled Starting… action while waking', () => {
    const gate = makeGate('waking', true)
    renderPanel(gate)
    expect(screen.getByRole('heading', { name: WAKING_TITLE })).toBeInTheDocument()
    const action = screen.getByRole('button', { name: 'Starting…' })
    expect(action).toHaveAttribute('aria-disabled', 'true')
  })

  it('shows the stuck copy and Try again', () => {
    const gate = makeGate('stuck')
    renderPanel(gate)
    expect(screen.getByRole('heading', { name: STUCK_TITLE })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('rotates the intent message every 4 seconds while waking', () => {
    renderPanel(makeGate('waking'))
    expect(screen.getByText(WAKING_MESSAGES[0])).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(4000))
    expect(screen.getByText(WAKING_MESSAGES[1])).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(4000))
    expect(screen.getByText(WAKING_MESSAGES[2])).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(4000))
    expect(screen.getByText(WAKING_MESSAGES[0], { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(WAKING_MESSAGES[0])
  })

  it('keeps a static message under reduced motion', () => {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: true,
      media: '(prefers-reduced-motion: reduce)',
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    } as unknown as MediaQueryList)

    renderPanel(makeGate('waking'))
    act(() => vi.advanceTimersByTime(8000))
    // Under reduced motion the message never rotates past the first entry.
    expect(screen.getByText(WAKING_MESSAGES[0])).toBeInTheDocument()
    expect(screen.queryByText(WAKING_MESSAGES[1])).not.toBeInTheDocument()
  })

  it('provides a polite live region and hides the elapsed timer from announcements', () => {
    renderPanel(makeGate('waking'))
    const live = screen.getByRole('status')
    expect(live).toHaveAttribute('aria-live', 'polite')
    expect(live).toHaveTextContent(WAKING_TITLE)
    // The elapsed timer is visually shown but never announced.
    expect(screen.getByText(/0:00/)).toHaveAttribute('aria-hidden', 'true')
  })

  it('returns focus to the search action when the focused panel action is removed', () => {
    const input = document.createElement('input')
    input.id = 'search-input'
    document.body.appendChild(input)

    const gate = makeGate('stuck')
    const { unmount } = render(
      <AvailabilityGateContext.Provider value={gate}>
        <AvailabilityPanel />
      </AvailabilityGateContext.Provider>,
    )
    const retry = screen.getByRole('button', { name: 'Try again' })
    retry.focus()
    expect(document.activeElement).toBe(retry)

    unmount()
    expect(document.activeElement).toBe(input)
    input.remove()
  })
})
