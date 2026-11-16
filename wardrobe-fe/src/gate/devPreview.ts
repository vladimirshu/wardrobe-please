import type { GatePhase } from './useAvailabilityGate'

const OVERRIDES: Record<string, GatePhase> = {
  checking: 'checking',
  closed: 'closed',
  waking: 'waking',
  healthy: 'healthy',
  stuck: 'stuck',
}

/**
 * Development-only readiness preview. Reads a `?gate=` query parameter so a
 * developer can force a phase without a live backend or control plane. This is
 * compiled out of production builds: `useAvailabilityGate` only calls it under
 * `import.meta.env.DEV`, so the branch (and this module) is tree-shaken.
 *
 * Values: `checking`, `closed`, `waking`, `healthy`, `stuck`.
 */
export function readGatePreviewOverride(): GatePhase | null {
  const value = new URLSearchParams(window.location.search).get('gate')
  if (!value) return null
  return OVERRIDES[value.toLowerCase()] ?? null
}
