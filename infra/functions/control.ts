import { EC2Client, DescribeInstancesCommand, StartInstancesCommand } from '@aws-sdk/client-ec2';

const client = new EC2Client({ maxAttempts: 2, requestHandler: { connectionTimeout: 1000, requestTimeout: 3000 } });
type Dependencies = { ec2: Pick<EC2Client, 'send'>; fetch: typeof fetch; env: NodeJS.ProcessEnv;
  log: (message: string, error?: unknown) => void };

export function healthUrl(raw: string | undefined): string {
  if (!raw) throw new Error('HEALTHCHECK_URL is required');
  const url = new URL(raw);
  if (url.protocol !== 'https:' || !/^d[a-z0-9]+\.cloudfront\.net$/.test(url.hostname)
      || url.port || url.username || url.password || url.pathname !== '/api/v1/health' || url.search || url.hash) {
    throw new Error('HEALTHCHECK_URL must be the default CloudFront HTTPS /api/v1/health URL');
  }
  return url.href;
}

export function createHandler(deps: Dependencies) {
  return async () => {
    let state = 'STARTING';
    try {
      // Fail closed before waking a host whose readiness route has not been configured.
      const url = healthUrl(deps.env.HEALTHCHECK_URL);
      const id = deps.env.INSTANCE_ID;
      if (!id) throw new Error('INSTANCE_ID is required');
      const result = await deps.ec2.send(new DescribeInstancesCommand({ InstanceIds: [id] }));
      const instance = result.Reservations?.flatMap(r => r.Instances ?? []).find(i => i.InstanceId === id);
      if (instance?.State?.Name === 'stopped') {
        await deps.ec2.send(new StartInstancesCommand({ InstanceIds: [id] }));
      } else if (instance?.State?.Name === 'running') {
        const response = await deps.fetch(url, {
          redirect: 'manual', signal: AbortSignal.timeout(2000), cache: 'no-store',
        });
        // Only the status is needed. Discard the body to release the connection.
        await response.body?.cancel();
        if (response.status === 200) state = 'UP';
      }
    } catch (error) {
      deps.log('Control configuration, EC2 or readiness check failed', error instanceof Error ? error.name + ': ' + error.message : 'Unknown error');
    }
    return { statusCode: 200, headers: { 'content-type': 'application/json',
      'cache-control': 'no-store, no-cache, must-revalidate', pragma: 'no-cache', expires: '0' },
      body: JSON.stringify({ state }) };
  };
}

export const handler = createHandler({ ec2: client, fetch: globalThis.fetch, env: process.env, log: console.error });
