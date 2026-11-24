import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { App } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { ControlPlaneStack } from '../lib/control-plane-stack';

test('control plane is private, ARM64, outside VPC with scoped roles and enabled zone-aware schedules', () => {
  const t = Template.fromStack(new ControlPlaneStack(new App(), 'TestControl', {
    env: { account: '708564192046', region: 'eu-central-1' },
  }));
  t.resourceCountIs('AWS::Lambda::Function', 2);
  t.allResourcesProperties('AWS::Lambda::Function', { Architectures: ['arm64'], Runtime: 'nodejs22.x', VpcConfig: Match.absent() });
  t.hasResourceProperties('AWS::Lambda::Url', { AuthType: 'AWS_IAM', Cors: Match.absent() });
  t.resourceCountIs('AWS::Scheduler::Schedule', 3);
  for (const hour of [8, 22]) t.hasResourceProperties('AWS::Scheduler::Schedule', {
    ScheduleExpression: `cron(0 ${hour} ? * MON,TUE,WED,THU,FRI *)`, ScheduleExpressionTimezone: 'Europe/Berlin',
    FlexibleTimeWindow: { Mode: 'OFF' }, Target: Match.objectLike({ RetryPolicy: { MaximumEventAgeInSeconds: 60, MaximumRetryAttempts: 0 } }),
  });
  t.hasResourceProperties('AWS::Scheduler::Schedule', { ScheduleExpression: 'rate(5 minutes)' });
  const json = t.toJSON();
  assert.equal(json.Parameters.SchedulesEnabled.Default, 'true');
  const statements = Object.values(t.findResources('AWS::IAM::Policy')).flatMap((r: any) => r.Properties.PolicyDocument.Statement);
  for (const action of ['ec2:StartInstances', 'ec2:StopInstances']) {
    const matching = statements.filter(s => JSON.stringify(s.Action).includes(action));
    assert.ok(matching.length >= 2);
    for (const s of matching) {
      assert.notEqual(s.Resource, '*');
      assert.match(JSON.stringify(s.Resource), /WardrobeBackendInstanceId/);
    }
  }
  assert.ok(statements.some(s => s.Action === 'ec2:DescribeInstances' && s.Resource === '*'));
  assert.doesNotMatch(JSON.stringify(json), /ssm:|secretsmanager:|ORIGIN_SECRET|PUBLIC_IP|dynamodb:/);
  t.hasResourceProperties('AWS::IAM::Role', { AssumeRolePolicyDocument: Match.objectLike({ Statement: Match.arrayWith([
    Match.objectLike({ Principal: { Service: 'scheduler.amazonaws.com' }, Condition: {
      StringEquals: { 'aws:SourceAccount': '708564192046' }, ArnEquals: { 'aws:SourceArn': Match.anyValue() },
    } }),
  ]) }) });
  const permissions = Object.values(t.findResources('AWS::Lambda::Permission')) as any[];
  assert.equal(permissions.length, 2);
  assert.ok(permissions.every(p => p.Condition === 'EdgeBound' && p.Properties.Principal === 'cloudfront.amazonaws.com'
    && JSON.stringify(p.Properties.SourceArn).includes('DistributionId')));
  t.hasResourceProperties('AWS::Lambda::Permission', { Action: 'lambda:InvokeFunction', InvokedViaFunctionUrl: true });
  t.hasResourceProperties('AWS::Lambda::Permission', { Action: 'lambda:InvokeFunctionUrl', FunctionUrlAuthType: 'AWS_IAM' });
  assert.ok(JSON.stringify(json.Outputs).includes('WardrobeControlFunctionUrl'));
});
