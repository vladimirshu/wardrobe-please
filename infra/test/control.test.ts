import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { mock } from 'node:test';
import { EC2Client, DescribeInstancesCommand, StartInstancesCommand } from '@aws-sdk/client-ec2';
import { createHandler, healthUrl } from '../functions/control';

const env = { INSTANCE_ID: 'i-test', HEALTHCHECK_URL: 'https://d123abc.cloudfront.net/api/v1/health' };
function setup(state: string, probe: typeof fetch = async () => new Response('', { status: 200 }), config = env) {
  const commands: unknown[] = [];
  const send = async (command: unknown) => {
    commands.push(command);
    return { Reservations: [{ Instances: [{ InstanceId: 'i-test', State: { Name: state } }] }] };
  };
  const log = mock.fn();
  const fetch = mock.fn(probe);
  const handler = createHandler({ ec2: { send } as Pick<EC2Client, 'send'>, fetch, env: config, log });
  return { handler, commands, fetch, log };
}

test('stopped starts once per call; transitions never issue start or probe', async () => {
  for (const state of ['stopped', 'pending', 'stopping', 'terminated', 'unknown']) {
    const s = setup(state);
    const response = await s.handler();
    assert.equal(response.statusCode, 200);
    assert.equal(response.body, '{"state":"STARTING"}');
    assert.match(response.headers['cache-control'], /no-store/);
    assert.ok(s.commands[0] instanceof DescribeInstancesCommand);
    assert.equal(s.commands.length, state === 'stopped' ? 2 : 1);
    if (state === 'stopped') {
      assert.ok(s.commands[1] instanceof StartInstancesCommand);
      assert.deepEqual(s.commands[1].input, { InstanceIds: ['i-test'] });
    }
    assert.equal(s.fetch.mock.callCount(), 0);
  }
});

test('running probes only configured HTTPS CloudFront health; redirects disabled and no secret headers', async () => {
  const s = setup('running');
  for (let i = 0; i < 2; i++) assert.equal((await s.handler()).body, '{"state":"UP"}');
  assert.equal(s.commands.length, 2); // Describes only; repeated UP does not extend a session.
  const [url, options] = s.fetch.mock.calls[0].arguments;
  assert.equal(url, env.HEALTHCHECK_URL);
  assert.equal(options?.redirect, 'manual');
  assert.equal(options?.cache, 'no-store');
  assert.equal(options?.headers, undefined);
  assert.ok(options?.signal instanceof AbortSignal);
});

test('redirects, errors and unavailable readiness stay STARTING', async () => {
  for (const status of [301, 302, 307, 503]) {
    const s = setup('running', async () => new Response(null, { status }));
    assert.equal((await s.handler()).body, '{"state":"STARTING"}');
  }
  const s = setup('running', async () => { throw new Error('connection refused'); });
  assert.equal((await s.handler()).body, '{"state":"STARTING"}');
  assert.equal(s.log.mock.callCount(), 1);
});

test('probe aborts after two seconds and answers HTTP 200 STARTING', async () => {
  const s = setup('running', async (_url, options) => new Promise((_resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('probe did not abort')), 3000);
    options?.signal?.addEventListener('abort', () => { clearTimeout(timer); reject(options.signal?.reason); });
  }));
  const response = await s.handler();
  assert.equal(response.statusCode, 200);
  assert.equal(response.body, '{"state":"STARTING"}');
});

test('missing/invalid health URL fails closed and logs configuration error', async () => {
  for (const url of ['', 'http://d123.cloudfront.net/api/v1/health', 'https://127.0.0.1/api/v1/health',
    'https://d123.cloudfront.net/api/v1/control/up', 'https://user@d123.cloudfront.net/api/v1/health',
    'https://d123.cloudfront.net/api/v1/health?x=y', 'invalid']) {
    assert.throws(() => healthUrl(url));
    const s = setup('stopped', undefined, { ...env, HEALTHCHECK_URL: url });
    assert.equal((await s.handler()).body, '{"state":"STARTING"}');
    assert.equal(s.commands.length, 0);
    assert.equal(s.log.mock.callCount(), 1);
  }
});

test('describe and start AWS failures, including concurrent-start race, return 200 STARTING', async () => {
  for (const failOn of [DescribeInstancesCommand, StartInstancesCommand]) {
    const handler = createHandler({ ec2: { send: async (command: unknown) => {
      if (command instanceof failOn) throw new Error('AWS failed');
      return { Reservations: [{ Instances: [{ InstanceId: env.INSTANCE_ID, State: { Name: 'stopped' } }] }] };
    } } as Pick<EC2Client, 'send'>, fetch: globalThis.fetch, env, log: () => {} });
    assert.equal((await handler()).body, '{"state":"STARTING"}');
  }
});
