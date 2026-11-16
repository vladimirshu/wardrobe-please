import { parseSchedule, type ScheduleConfig } from '../gate/schedule'
import openingHours from './opening-hours.json'

/** Used by both the storefront and Vite's build validation. */
export function loadSchedule(): ScheduleConfig {
  const result = parseSchedule(openingHours)
  if (result.ok) return result.config
  throw new Error(`Invalid availability schedule in src/config/opening-hours.json: ${result.error}`)
}
