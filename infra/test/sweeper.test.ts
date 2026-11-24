import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { EC2Client, StopInstancesCommand } from '@aws-sdk/client-ec2';
import { createHandler } from '../functions/sweeper';
import { readOpeningHours, isOpen, scheduleCron, validateOpeningHours } from '../lib/opening-hours';

const hours = readOpeningHours();
test('shared schedule validates days, times and IANA zone', () => {
  assert.equal(scheduleCron(hours, 'from'), 'cron(0 8 ? * MON,TUE,WED,THU,FRI *)');
  assert.equal(scheduleCron(hours, 'to'), 'cron(0 22 ? * MON,TUE,WED,THU,FRI *)');
  for (const patch of [{ tz: '' }, { tz: 'Invalid/Zone' }, { days: 'mon,mon' }, { days: 'monday' },
    { from: '24:00' }, { to: '08:00' }, { from: undefined }, { to: ['22:00'] }]) {
    assert.throws(() => validateOpeningHours({ ...hours, ...patch }));
  }
  const alternative = validateOpeningHours({ tz: 'UTC', days: 'sat,sun', from: '10:30', to: '16:45' });
  assert.equal(scheduleCron(alternative, 'from'), 'cron(30 10 ? * SAT,SUN *)');
  assert.equal(isOpen(new Date('2026-11-28T10:30:00Z'), alternative), true);
  assert.equal(isOpen(new Date('2026-11-28T16:45:00Z'), alternative), false);
});

test('start-inclusive, end-exclusive weekdays, weekends and both DST transitions', () => {
  for (const [instant, expected] of [
    ['2026-11-24T06:59:00Z', false], ['2026-11-24T07:00:00Z', true],
    ['2026-11-24T20:59:00Z', true], ['2026-11-24T21:00:00Z', false],
    ['2026-11-28T10:00:00Z', false], ['2026-11-29T10:00:00Z', false],
    ['2026-03-29T01:30:00Z', false], ['2026-10-25T01:30:00Z', false],
    ['2026-03-30T06:00:00Z', true], ['2026-10-26T07:00:00Z', true],
  ] as const) assert.equal(isOpen(new Date(instant), hours), expected, instant);
});

test('sweeper stops only running off-hours hosts at >=20 minutes, regardless of visitor activity', async () => {
  for (const [now, minutes, state, stop] of [
    ['2026-11-24T22:00:00Z', 19, 'running', false],
    ['2026-11-24T22:00:00Z', 20, 'running', true],
    ['2026-11-24T12:00:00Z', 90, 'running', false],
    ['2026-11-24T21:00:00Z', 10, 'running', false], // weekday 21:50 wake: closing schedule stops it.
    ['2026-11-28T21:00:00Z', 10, 'running', false], // weekend has no closing schedule.
    ['2026-11-28T21:10:00Z', 20, 'running', true],
    ['2026-11-28T21:00:00Z', 60, 'stopping', false],
    ['2026-11-28T21:00:00Z', 60, 'stopped', false],
    ['2026-11-28T21:00:00Z', 60, 'pending', false],
    ['2026-11-28T21:00:00Z', -1, 'running', false],
  ] as const) {
    const commands: unknown[] = [];
    const clock = new Date(now);
    const handler = createHandler({ env: { INSTANCE_ID: 'i-test', OPENING_HOURS: JSON.stringify(hours) },
      now: () => clock, ec2: { send: async (command: unknown) => {
        commands.push(command);
        return { Reservations: [{ Instances: [{ InstanceId: 'i-test', State: { Name: state },
          LaunchTime: new Date(clock.getTime() - minutes * 60_000) }] }] };
      } } as Pick<EC2Client, 'send'> });
    await handler();
    assert.equal(commands.some(c => c instanceof StopInstancesCommand), stop, `${now}: ${minutes} ${state}`);
  }
});
