import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { App } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { BackendHostStack } from '../lib/backend-host-stack';

function template() {
  const context = JSON.parse(readFileSync('cdk.context.json', 'utf8')) as Record<string, unknown>;
  const app = new App({ context });
  return Template.fromStack(new BackendHostStack(app, 'TestHost', {
    env: { account: '708564192046', region: 'eu-central-1' },
  }));
}

test('only private VPC origin 8080 ingress, encrypted gp3, IMDSv2 and standard CPU credits', () => {
  const t = template();
  t.resourceCountIs('AWS::EC2::SecurityGroupIngress', 1);
  t.hasResourceProperties('AWS::EC2::SecurityGroupIngress', { IpProtocol: 'tcp', FromPort: 8080, ToPort: 8080,
    SourceSecurityGroupId: Match.anyValue(), SourcePrefixListId: Match.absent(), CidrIp: Match.absent() });
  t.hasResourceProperties('AWS::EC2::SecurityGroup', { SecurityGroupIngress: Match.absent() });
  t.hasResourceProperties('AWS::CloudFront::VpcOrigin', { VpcOriginEndpointConfig: {
    Arn: Match.anyValue(), HTTPPort: 8080, OriginProtocolPolicy: 'http-only', Name: Match.anyValue(),
  } });
  const serialized = JSON.stringify(t.toJSON());
  assert.ok(serialized.includes('CloudFront-VPCOrigins-Service-SG'));
  assert.ok(!serialized.includes('cloudfront.origin-facing'));
  assert.ok(!serialized.includes('origin-verify-secret'));
  assert.ok(serialized.includes('WardrobeBackendVpcOriginId'));
  assert.ok(serialized.includes('WardrobeBackendOriginPrivateDns'));
  t.hasResourceProperties('AWS::EC2::Instance', {
    MetadataOptions: { HttpTokens: 'required', HttpPutResponseHopLimit: 1 },
    CreditSpecification: { CPUCredits: 'standard' },
    BlockDeviceMappings: [{ DeviceName: '/dev/xvda', Ebs: { DeleteOnTermination: true, Encrypted: true,
      VolumeSize: 8, VolumeType: 'gp3' } }],
  });
  t.hasResourceProperties('AWS::EC2::LaunchTemplate', { LaunchTemplateData: { MetadataOptions: { HttpTokens: 'required' } } });
  t.resourceCountIs('AWS::EC2::EIP', 1);
  t.resourceCountIs('AWS::EC2::EIPAssociation', 1);
});

test('private retained immutable ECR keeps three images; secret access is exact', () => {
  const t = template();
  t.hasResourceProperties('AWS::ECR::Repository', { ImageTagMutability: 'IMMUTABLE',
    LifecyclePolicy: { LifecyclePolicyText: Match.serializedJson({ rules: [Match.objectLike({
      selection: Match.objectLike({ countNumber: 3, countType: 'imageCountMoreThan' }) })] }) } });
  const resources = t.findResources('AWS::IAM::Policy');
  const statements = Object.values(resources).flatMap(resource => resource.Properties.PolicyDocument.Statement);
  const deny = statements.find(statement => statement.Effect === 'Deny' && statement.Action.includes('ssm:GetParameters'));
  assert.equal(deny.NotResource.length, 2);
  const policies = JSON.stringify(resources);
  assert.ok(policies.includes('ssm:GetParameters'));
  assert.ok(policies.includes('ecom/prod/openai-api-key'));
  assert.ok(!policies.includes('ssm:GetParametersByPath'));
  assert.ok(!policies.includes('parameter/ecom/prod/*'));
});

test('bootstrap and supervision retain memory, secret and log constraints', () => {
  const service = readFileSync('scripts/backend.service', 'utf8');
  assert.ok(service.includes('--memory=640m --memory-swap=640m'));
  assert.ok(service.includes('Restart=always'));
  assert.ok(!service.includes('JAVA_TOOL_OPTIONS'));
  const t = template().toJSON();
  const serialized = JSON.stringify(t);
  assert.ok(serialized.includes('max-size'));
  assert.ok(serialized.includes('SPRING_PROFILES_ACTIVE'));
  assert.ok(!serialized.includes('sk-proj-'));
});

test('service-group lookup is VPC-scoped and ordered after origin creation', () => {
  const t = template();
  const originId = Object.keys(t.findResources('AWS::CloudFront::VpcOrigin'))[0];
  const [lookupId, lookup] = Object.entries(t.findResources('Custom::AWS'))[0];
  const call = JSON.parse(lookup.Properties.Create);
  assert.equal(call.service, 'EC2');
  assert.equal(call.action, 'describeSecurityGroups');
  assert.deepEqual(call.parameters.Filters, [
    { Name: 'vpc-id', Values: ['vpc-020442000b132b04e'] },
    { Name: 'group-name', Values: ['CloudFront-VPCOrigins-Service-SG'] },
  ]);
  assert.ok(lookup.DependsOn.includes(originId));
  t.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
    SourceSecurityGroupId: { 'Fn::GetAtt': [lookupId, 'SecurityGroups.0.GroupId'] },
  });
  t.resourceCountIs('AWS::EC2::SecurityGroup', 1); // Only ours; AWS owns its service group.
});
