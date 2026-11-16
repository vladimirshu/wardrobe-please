import { describe, expect, it } from 'vitest'
import {
  isWithinOpeningHours,
  openingHoursLabel,
  parseSchedule,
  tzOffsetMinutes,
  type ScheduleConfig,
} from './schedule'

function weekdayConfig(tz: string): ScheduleConfig {
  const result = parseSchedule({ tz, days: 'mon,tue,wed,thu,fri', from: '08:00', to: '22:00' })
  if (!result.ok) throw new Error(result.error)
  return result.config
}

const berlin = () => weekdayConfig('Europe/Berlin')
const newYork = () => weekdayConfig('America/New_York')
const tokyo = () => weekdayConfig('Asia/Tokyo')

describe('opening window across time zones', () => {
  describe('Europe/Berlin (DST, UTC+1 winter, UTC+2 summer)', () => {
    it('opens at the opening instant and excludes the closing instant in winter', () => {
      // 2026-01-30 is a Friday; CET is UTC+1.
      expect(isWithinOpeningHours(new Date('2026-01-30T07:00:00Z'), berlin())).toBe(true) // 08:00
      expect(isWithinOpeningHours(new Date('2026-01-30T06:59:00Z'), berlin())).toBe(false) // 07:59
      expect(isWithinOpeningHours(new Date('2026-01-30T21:00:00Z'), berlin())).toBe(false) // 22:00
    })

    it('opens at 06:00 UTC in summer (CEST, UTC+2)', () => {
      expect(isWithinOpeningHours(new Date('2026-07-31T06:00:00Z'), berlin())).toBe(true) // 08:00
      expect(isWithinOpeningHours(new Date('2026-07-31T05:59:00Z'), berlin())).toBe(false) // 07:59
    })

    it('follows daylight-saving rules', () => {
      // The same UTC clock time is inside in summer and outside in winter.
      expect(isWithinOpeningHours(new Date('2026-01-30T06:30:00Z'), berlin())).toBe(false) // 07:30 CET
      expect(isWithinOpeningHours(new Date('2026-07-31T06:30:00Z'), berlin())).toBe(true) // 08:30 CEST
      expect(tzOffsetMinutes(new Date('2026-01-30T12:00:00Z'), 'Europe/Berlin')).toBe(60)
      expect(tzOffsetMinutes(new Date('2026-07-31T12:00:00Z'), 'Europe/Berlin')).toBe(120)
    })

    it('is closed on weekends', () => {
      expect(isWithinOpeningHours(new Date('2026-01-31T11:00:00Z'), berlin())).toBe(false) // Sat 12:00
    })
  })

  describe('America/New_York (DST, UTC-5 winter, UTC-4 summer)', () => {
    it('opens at the opening instant and excludes the closing instant in winter', () => {
      // 2026-01-30 is a Friday; EST is UTC-5.
      expect(isWithinOpeningHours(new Date('2026-01-30T13:00:00Z'), newYork())).toBe(true) // 08:00
      expect(isWithinOpeningHours(new Date('2026-01-30T12:59:00Z'), newYork())).toBe(false) // 07:59
      expect(isWithinOpeningHours(new Date('2026-01-31T03:00:00Z'), newYork())).toBe(false) // Fri 22:00
    })

    it('opens at 12:00 UTC in summer (EDT, UTC-4)', () => {
      expect(isWithinOpeningHours(new Date('2026-07-31T12:00:00Z'), newYork())).toBe(true) // 08:00
      expect(isWithinOpeningHours(new Date('2026-07-31T11:59:00Z'), newYork())).toBe(false) // 07:59
    })

    it('follows daylight-saving rules', () => {
      expect(isWithinOpeningHours(new Date('2026-01-30T12:30:00Z'), newYork())).toBe(false) // 07:30 EST
      expect(isWithinOpeningHours(new Date('2026-07-31T12:30:00Z'), newYork())).toBe(true) // 08:30 EDT
    })

    it('is closed on weekends', () => {
      expect(isWithinOpeningHours(new Date('2026-08-01T16:00:00Z'), newYork())).toBe(false) // Sat 12:00 EDT
    })
  })

  describe('Asia/Tokyo (no DST, UTC+9)', () => {
    it('opens at the opening instant and excludes the closing instant', () => {
      // 2026-07-31 is a Friday; JST is UTC+9, so 08:00 Tokyo is 23:00 UTC the day before.
      expect(isWithinOpeningHours(new Date('2026-07-30T23:00:00Z'), tokyo())).toBe(true) // Fri 08:00
      expect(isWithinOpeningHours(new Date('2026-07-30T22:59:00Z'), tokyo())).toBe(false) // Fri 07:59
      expect(isWithinOpeningHours(new Date('2026-07-31T13:00:00Z'), tokyo())).toBe(false) // Fri 22:00
    })

    it('keeps a constant offset with no daylight-saving change', () => {
      expect(tzOffsetMinutes(new Date('2026-01-30T12:00:00Z'), 'Asia/Tokyo')).toBe(540)
      expect(tzOffsetMinutes(new Date('2026-07-31T12:00:00Z'), 'Asia/Tokyo')).toBe(540)
    })

    it('is closed on weekends', () => {
      expect(isWithinOpeningHours(new Date('2026-08-01T03:00:00Z'), tokyo())).toBe(false) // Sat 12:00
    })
  })

  describe('weekday shift across zones', () => {
    it('classifies the same instant differently per configured zone', () => {
      const instant = new Date('2026-07-31T20:00:00Z')
      expect(isWithinOpeningHours(instant, berlin())).toBe(false) // Fri 22:00 Berlin (closing)
      expect(isWithinOpeningHours(instant, newYork())).toBe(true) // Fri 16:00 New York
      expect(isWithinOpeningHours(instant, tokyo())).toBe(false) // Sat 05:00 Tokyo (weekend)
    })
  })
})

describe('openingHoursLabel', () => {
  it('converts Berlin hours to the visitor zone deterministically', () => {
    // Friday 2026-01-30, winter: 08:00–22:00 Berlin is 02:00–16:00 New York.
    const label = openingHoursLabel(berlin(), new Date('2026-01-30T12:00:00Z'), 'America/New_York')
    expect(label).toEqual({ days: 'Mon–Fri', from: '02:00', to: '16:00', zone: 'America/New_York' })
  })

  it('reflects a weekday window crossing midnight in a far visitor zone', () => {
    // 08:00–22:00 Berlin is 16:00–06:00 (next day) in Tokyo.
    const label = openingHoursLabel(berlin(), new Date('2026-01-30T12:00:00Z'), 'Asia/Tokyo')
    expect(label.from).toBe('16:00')
    expect(label.to).toBe('06:00')
    expect(label.zone).toBe('Asia/Tokyo')
    expect(label.endsNextDay).toBe(true)
  })

  it('shifts the local opening weekdays when the opening instant is on the previous day', () => {
    const config = { ...berlin(), fromMinutes: 60 }
    const label = openingHoursLabel(config, new Date('2026-01-30T12:00:00Z'), 'America/New_York')
    expect(label).toEqual({
      days: 'Sun, Mon, Tue, Wed, Thu',
      from: '19:00',
      to: '16:00',
      zone: 'America/New_York',
      endsNextDay: true,
    })
  })
})
