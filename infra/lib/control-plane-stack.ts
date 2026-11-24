import { join } from 'node:path';
import { CfnCondition, CfnOutput, CfnParameter, Duration, Fn, RemovalPolicy, Stack, StackProps, Tags,
  aws_iam as iam, aws_lambda as lambda, aws_lambda_nodejs as nodejs,
  aws_logs as logs, aws_scheduler as scheduler } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { readOpeningHours, scheduleCron } from './opening-hours';

export class ControlPlaneStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);
    Tags.of(this).add('Project', 'wardrobe-please');
    const instanceId = Fn.importValue('WardrobeBackendInstanceId');
    const instanceArn = this.formatArn({ service: 'ec2', resource: 'instance', resourceName: instanceId });
    const hours = readOpeningHours();
    const enabled = new CfnParameter(this, 'SchedulesEnabled', { type: 'String', default: 'true', allowedValues: ['true', 'false'] });
    const active = new CfnCondition(this, 'SchedulesActive', { expression: Fn.conditionEquals(enabled.valueAsString, 'true') });
    // Plain parameters support a second deployment after edge creation without a reverse stack reference.
    const distributionId = new CfnParameter(this, 'DistributionId', { default: '', allowedPattern: '(|E[A-Z0-9]+)' });
    const health = new CfnParameter(this, 'HealthcheckUrl', { default: '',
      allowedPattern: '(|https://d[a-z0-9]+\\.cloudfront\\.net/api/v1/health)' });
    const bound = new CfnCondition(this, 'EdgeBound', { expression: Fn.conditionNot(Fn.conditionEquals(distributionId.valueAsString, '')) });
    const makeFunction = (name: string, action: string, environment: Record<string, string>) => {
      const logGroup = new logs.LogGroup(this, `${name}Logs`, { retention: logs.RetentionDays.ONE_WEEK, removalPolicy: RemovalPolicy.DESTROY });
      const role = new iam.Role(this, `${name}Role`, { assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com') });
      logGroup.grantWrite(role);
      // DescribeInstances does not support resource-level permissions; mutating calls do.
      role.addToPolicy(new iam.PolicyStatement({ actions: ['ec2:DescribeInstances'], resources: ['*'] }));
      role.addToPolicy(new iam.PolicyStatement({ actions: [action], resources: [instanceArn] }));
      return new nodejs.NodejsFunction(this, name, {
        entry: join(__dirname, `../functions/${name.toLowerCase()}.ts`), handler: 'handler',
        runtime: lambda.Runtime.NODEJS_22_X, architecture: lambda.Architecture.ARM_64,
        memorySize: 128, timeout: Duration.seconds(15), role, logGroup,
        environment: { INSTANCE_ID: instanceId, ...environment },
        bundling: { minify: true, sourceMap: true, bundleAwsSDK: true },
      });
    };
    const control = makeFunction('Control', 'ec2:StartInstances', { HEALTHCHECK_URL: health.valueAsString });
    const sweeper = makeFunction('Sweeper', 'ec2:StopInstances', { OPENING_HOURS: JSON.stringify(hours) });
    const url = control.addFunctionUrl({ authType: lambda.FunctionUrlAuthType.AWS_IAM });
    const distributionArn = this.formatArn({ service: 'cloudfront', region: '', resource: 'distribution', resourceName: distributionId.valueAsString });
    for (const [name, action] of [['Url', 'lambda:InvokeFunctionUrl'], ['Function', 'lambda:InvokeFunction']]) {
      const permission = new lambda.CfnPermission(this, `CloudFront${name}`, {
        action, functionName: control.functionName, principal: 'cloudfront.amazonaws.com',
        sourceArn: distributionArn,
        ...(name === 'Url' ? { functionUrlAuthType: 'AWS_IAM' } : { invokedViaFunctionUrl: true }),
      });
      permission.cfnOptions.condition = bound;
    }
    const group = new scheduler.CfnScheduleGroup(this, 'ScheduleGroup');
    const schedulerRole = (name: string) => new iam.Role(this, name, {
      assumedBy: new iam.ServicePrincipal('scheduler.amazonaws.com', { conditions: {
        StringEquals: { 'aws:SourceAccount': this.account }, ArnEquals: { 'aws:SourceArn': group.attrArn },
      } }),
    });
    const ec2Role = schedulerRole('InstanceScheduleRole');
    ec2Role.addToPolicy(new iam.PolicyStatement({ actions: ['ec2:StartInstances', 'ec2:StopInstances'], resources: [instanceArn] }));
    const sweeperRole = schedulerRole('SweeperScheduleRole');
    sweeper.grantInvoke(sweeperRole);
    const schedule = (name: string, expression: string, arn: string, role: iam.Role, input: string) => {
      const resource = new scheduler.CfnSchedule(this, name, {
        groupName: group.ref, scheduleExpression: expression, scheduleExpressionTimezone: hours.tz,
        flexibleTimeWindow: { mode: 'OFF' }, state: Fn.conditionIf(active.logicalId, 'ENABLED', 'DISABLED').toString(),
        target: { arn, roleArn: role.roleArn, input, retryPolicy: { maximumEventAgeInSeconds: 60, maximumRetryAttempts: 0 } },
      });
      resource.node.addDependency(role);
    };
    for (const [name, boundary, action] of [['Start', 'from', 'startInstances'], ['Stop', 'to', 'stopInstances']] as const) {
      schedule(name, scheduleCron(hours, boundary), `arn:${this.partition}:scheduler:::aws-sdk:ec2:${action}`,
        ec2Role, JSON.stringify({ InstanceIds: [instanceId] }));
    }
    schedule('Sweep', 'rate(5 minutes)', sweeper.functionArn, sweeperRole, '{}');
    new CfnOutput(this, 'FunctionUrl', { value: url.url, exportName: 'WardrobeControlFunctionUrl' });
    new CfnOutput(this, 'FunctionArn', { value: control.functionArn, exportName: 'WardrobeControlFunctionArn' });
    new CfnOutput(this, 'FunctionName', { value: control.functionName });
    new CfnOutput(this, 'ScheduleGroupName', { value: group.ref });
  }
}
