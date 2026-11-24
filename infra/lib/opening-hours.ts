import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export interface OpeningHours { tz: string; days: string; from: string; to: string }
const weekdays = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

export function validateOpeningHours(value: unknown): OpeningHours {
  const config = value as OpeningHours | null;
  if (!config || typeof config.tz !== 'string' || !/^[A-Za-z_]+(?:\/[A-Za-z0-9_+-]+)*$/.test(config.tz)) {
    throw new Error('Opening hours require an IANA time zone');
  }
  try { new Intl.DateTimeFormat('en', { timeZone: config.tz }).format(); }
  catch { throw new Error(`Invalid opening-hours IANA time zone: ${config.tz}`); }
  const days = typeof config.days === 'string' ? config.days.split(',') : [];
  if (!days.length || days.some(day => !weekdays.includes(day)) || new Set(days).size !== days.length) {
    throw new Error('Opening hours require unique comma-separated days (mon,tue,...)');
  }
  const time = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
  if (typeof config.from !== 'string' || typeof config.to !== 'string'
      || !time.test(config.from) || !time.test(config.to) || config.from >= config.to) {
    throw new Error('Opening hours require HH:mm times with from < to on the same day');
  }
  return { tz: config.tz, days: config.days, from: config.from, to: config.to };
}

export function readOpeningHours(): OpeningHours {
  return validateOpeningHours(JSON.parse(readFileSync(
    join(__dirname, '../../wardrobe-fe/src/config/opening-hours.json'), 'utf8')));
}

export function scheduleCron(config: OpeningHours, boundary: 'from' | 'to'): string {
  const [hour, minute] = config[boundary].split(':').map(Number);
  return `cron(${minute} ${hour} ? * ${config.days.toUpperCase()} *)`;
}

export function isOpen(now: Date, config: OpeningHours): boolean {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: config.tz, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(now);
  const part = (type: string) => parts.find(p => p.type === type)!.value;
  const time = `${part('hour')}:${part('minute')}`;
  return config.days.split(',').includes(part('weekday').toLowerCase()) && time >= config.from && time < config.to;
}
