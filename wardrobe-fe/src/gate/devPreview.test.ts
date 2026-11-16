// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { readGatePreviewOverride } from './devPreview'

function setSearch(search: string) {
  window.history.pushState({}, '', search)
}

describe('readGatePreviewOverride', () => {
  it('returns null without a gate parameter', () => {
    setSearch('/')
    expect(readGatePreviewOverride()).toBeNull()
  })

  it('parses known phases case-insensitively', () => {
    setSearch('/?gate=healthy')
    expect(readGatePreviewOverride()).toBe('healthy')
    setSearch('/?gate=STUCK')
    expect(readGatePreviewOverride()).toBe('stuck')
    setSearch('/?gate=waking')
    expect(readGatePreviewOverride()).toBe('waking')
    setSearch('/?gate=closed')
    expect(readGatePreviewOverride()).toBe('closed')
  })

  it('returns null for an unknown value', () => {
    setSearch('/?gate=bogus')
    expect(readGatePreviewOverride()).toBeNull()
  })

  it('ignores other query parameters', () => {
    setSearch('/?ref=atelier&gate=healthy')
    expect(readGatePreviewOverride()).toBe('healthy')
  })
})
