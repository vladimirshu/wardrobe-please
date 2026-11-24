import { EC2Client, DescribeInstancesCommand, StopInstancesCommand } from '@aws-sdk/client-ec2';
import { isOpen, validateOpeningHours } from '../lib/opening-hours';

const client = new EC2Client({ maxAttempts: 2, requestHandler: { connectionTimeout: 1000, requestTimeout: 3000 } });
export function createHandler(deps: { ec2: Pick<EC2Client, 'send'>; env: NodeJS.ProcessEnv; now: () => Date }) {
  return async () => {
    const config = validateOpeningHours(JSON.parse(deps.env.OPENING_HOURS ?? 'null'));
    const id = deps.env.INSTANCE_ID;
    if (!id) throw new Error('INSTANCE_ID is required');
    const now = deps.now();
    if (isOpen(now, config)) return;
    const result = await deps.ec2.send(new DescribeInstancesCommand({ InstanceIds: [id] }));
    const instance = result.Reservations?.flatMap(r => r.Instances ?? []).find(i => i.InstanceId === id);
    if (instance?.State?.Name === 'running' && instance.LaunchTime
        && now.getTime() - instance.LaunchTime.getTime() >= 20 * 60_000) {
      await deps.ec2.send(new StopInstancesCommand({ InstanceIds: [id] }));
    }
  };
}

export const handler = createHandler({ ec2: client, env: process.env, now: () => new Date() });
