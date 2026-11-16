export type DayName = 'mon' | 'tue' | 'wed' | 'thu' | 'fri' | 'sat' | 'sun'

const ALL_DAYS: DayName[] = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']

const DAY_LABELS: Record<DayName, string> = {
  mon: 'Mon',
  tue: 'Tue',
  wed: 'Wed',
  thu: 'Thu',
  fri: 'Fri',
  sat: 'Sat',
  sun: 'Sun',
}

export interface ScheduleConfig {
  tz: string
  days: DayName[]
  fromMinutes: number
  toMinutes: number
}

export type ScheduleResult = { ok: true; config: ScheduleConfig } | { ok: false; error: string }

export function parseDayName(value: string): DayName | null {
  const v = value.trim().toLowerCase()
  return (ALL_DAYS as string[]).includes(v) ? (v as DayName) : null
}

export function parseTime(value: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(value.trim())
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  if (h < 0 || h > 23 || min < 0 || min > 59) return null
  return h * 60 + min
}

export function isValidTimeZone(tz: string): boolean {
  if (/^[+-]/.test(tz)) return false
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz }).format()
    return true
  } catch {
    return false
  }
}

export function parseSchedule(input: {
  tz?: string
  days?: string
  from?: string
  to?: string
}): ScheduleResult {
  const tz = input.tz?.trim()
  if (!tz || !isValidTimeZone(tz)) {
    return { ok: false, error: `Invalid or missing IANA time zone: ${tz ?? ''}` }
  }
  const fromMinutes = parseTime(input.from ?? '')
  const toMinutes = parseTime(input.to ?? '')
  if (fromMinutes === null || toMinutes === null) {
    return { ok: false, error: 'Opening hours must be HH:MM (24-hour)' }
  }
  if (fromMinutes >= toMinutes) {
    return { ok: false, error: 'Closing time must be later than opening time on the same day' }
  }
  const days = (input.days ?? '')
    .split(',')
    .map(parseDayName)
  if (!days.every((day): day is DayName => day !== null) || new Set(days).size !== days.length) {
    return { ok: false, error: 'Opening days must be a comma-separated list of distinct days (mon–sun)' }
  }
  return { ok: true, config: { tz, days, fromMinutes, toMinutes } }
}

export interface ZonedParts {
  year: number
  month: number
  day: number
  weekday: number
  dayName: DayName
  hours: number
  minutes: number
}

const WEEKDAY_INDEX: Record<DayName, number> = {
  sun: 0,
  mon: 1,
  tue: 2,
  wed: 3,
  thu: 4,
  fri: 5,
  sat: 6,
}

export function zonedParts(date: Date, tz: string): ZonedParts {
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
  const map: Record<string, string> = {}
  for (const p of fmt.formatToParts(date)) map[p.type] = p.value
  let hours = parseInt(map.hour ?? '0', 10)
  if (hours === 24) hours = 0
  const dayName = ((map.weekday ?? 'mon').slice(0, 3).toLowerCase() || 'mon') as DayName
  return {
    year: Number(map.year),
    month: Number(map.month),
    day: Number(map.day),
    weekday: WEEKDAY_INDEX[dayName] ?? 1,
    dayName,
    hours,
    minutes: parseInt(map.minute ?? '0', 10),
  }
}

/** Whether the given instant falls inside the configured opening window. */
export function isWithinOpeningHours(date: Date, config: ScheduleConfig): boolean {
  const parts = zonedParts(date, config.tz)
  if (!config.days.includes(parts.dayName)) return false
  const minutes = parts.hours * 60 + parts.minutes
  return minutes >= config.fromMinutes && minutes < config.toMinutes
}

/** Time-zone offset (minutes east of UTC) at the given instant. */
export function tzOffsetMinutes(date: Date, tz: string): number {
  const parts = zonedParts(date, tz)
  const asUTC = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hours, parts.minutes)
  return Math.round((asUTC - date.getTime()) / 60000)
}

function wallClockInstant(
  year: number,
  month: number,
  day: number,
  hours: number,
  minutes: number,
  tz: string,
): Date {
  const guess = new Date(Date.UTC(year, month - 1, day, hours, minutes))
  const offset = tzOffsetMinutes(guess, tz)
  const result = new Date(guess.getTime() - offset * 60000)
  const offset2 = tzOffsetMinutes(result, tz)
  if (offset2 !== offset) return new Date(guess.getTime() - offset2 * 60000)
  return result
}

function minutesToHHMM(minutes: number): string {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

export function formatDays(days: DayName[]): string {
  if (days.length === ALL_DAYS.length) return 'Every day'
  const weekdays = ['mon', 'tue', 'wed', 'thu', 'fri']
  if (weekdays.every((d) => days.includes(d as DayName)) && days.every((d) => weekdays.includes(d))) {
    return 'Mon–Fri'
  }
  return days.map((d) => DAY_LABELS[d]).join(', ')
}

/** Opening hours converted to the visitor's local time zone for display. */
export function openingHoursLabel(
  config: ScheduleConfig,
  now = new Date(),
  visitorZone?: string,
): {
  days: string
  from: string
  to: string
  zone: string
  endsNextDay?: boolean
} {
  const zone = visitorZone ?? (new Intl.DateTimeFormat().resolvedOptions().timeZone || config.tz)
  const parts = zonedParts(now, config.tz)
  const fromDate = wallClockInstant(
    parts.year,
    parts.month,
    parts.day,
    Math.floor(config.fromMinutes / 60),
    config.fromMinutes % 60,
    config.tz,
  )
  const toDate = wallClockInstant(
    parts.year,
    parts.month,
    parts.day,
    Math.floor(config.toMinutes / 60),
    config.toMinutes % 60,
    config.tz,
  )
  const fromParts = zonedParts(fromDate, zone)
  const toParts = zonedParts(toDate, zone)
  const dayShift = fromParts.weekday - parts.weekday
  const visitorDays = config.days.map((day) => ALL_DAYS[(WEEKDAY_INDEX[day] + dayShift + 6) % 7])
  return {
    days: formatDays(visitorDays),
    from: minutesToHHMM(fromParts.hours * 60 + fromParts.minutes),
    to: minutesToHHMM(toParts.hours * 60 + toParts.minutes),
    zone,
    ...(fromParts.weekday !== toParts.weekday && { endsNextDay: true }),
  }
}
