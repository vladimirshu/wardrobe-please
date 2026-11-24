import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { App } from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { EdgeHostingStack } from '../lib/edge-hosting-stack';

test('private three-origin edge preserves API routing, payload forwarding and recovery timeouts without paid protection or analytics', () => {
  const t = Template.fromStack(new EdgeHostingStack(new App(), 'TestEdge', {
    env: { account: '708564192046', region: 'eu-central-1' },
  }));
  t.resourceCountIs('AWS::S3::Bucket', 1);
  t.hasResourceProperties('AWS::S3::Bucket', {
    PublicAccessBlockConfiguration: { BlockPublicAcls: true, BlockPublicPolicy: true,
      IgnorePublicAcls: true, RestrictPublicBuckets: true },
    WebsiteConfiguration: Match.absent(), LoggingConfiguration: Match.absent(),
  });
  for (const type of ['AWS::CloudFront::VpcOrigin', 'AWS::WAFv2::WebACL', 'AWS::Lambda::Function',
    'AWS::Lambda::Permission', 'AWS::CloudFront::ResponseHeadersPolicy', 'AWS::CloudFront::Function',
    'AWS::CloudWatch::Dashboard', 'AWS::CloudFront::MonitoringSubscription']) t.resourceCountIs(type, 0);
  t.resourceCountIs('AWS::CloudFront::OriginAccessControl', 2);
  for (const type of ['s3', 'lambda']) t.hasResourceProperties('AWS::CloudFront::OriginAccessControl', {
    OriginAccessControlConfig: Match.objectLike({ OriginAccessControlOriginType: type,
      SigningBehavior: 'always', SigningProtocol: 'sigv4' }),
  });
  const distribution: any = Object.values(t.findResources('AWS::CloudFront::Distribution'))[0];
  const config = distribution.Properties.DistributionConfig;
  assert.equal(config.DefaultRootObject, 'index.html');
  assert.equal(config.WebACLId, undefined);
  assert.equal(config.Logging, undefined);
  assert.equal(config.DefaultCacheBehavior.ResponseHeadersPolicyId, '67f7725c-6f97-4210-82d7-5512b31e9d03');
  assert.equal(config.DefaultCacheBehavior.CachePolicyId, '83da9c7e-98b4-4e11-a168-04f0df8e2c65');
  assert.deepEqual(config.CacheBehaviors.map((b: any) => b.PathPattern), ['/api/v1/control/*', '/api/v1/*']);
  for (const behavior of config.CacheBehaviors) {
    assert.equal(behavior.CachePolicyId, '4135ea2d-6df8-44a3-9df3-4b5a84be39ad');
    assert.equal(behavior.OriginRequestPolicyId, 'b689b0a8-53d0-40ab-baf2-68738e2966ac');
    assert.equal(behavior.ViewerProtocolPolicy, 'redirect-to-https');
    assert.equal(behavior.FunctionAssociations, undefined);
    assert.equal(behavior.LambdaFunctionAssociations, undefined);
  }
  assert.ok(config.CacheBehaviors[1].AllowedMethods.includes('POST'));
  assert.deepEqual(config.CacheBehaviors[0].AllowedMethods, ['GET', 'HEAD']);
  assert.equal(config.Origins.length, 3);
  const backend = config.Origins.find((o: any) => o.Id === 'Backend');
  assert.deepEqual(backend.VpcOriginConfig, { VpcOriginId: { 'Fn::ImportValue': 'WardrobeBackendVpcOriginId' }, OriginReadTimeout: 30 });
  assert.deepEqual(backend.DomainName, { 'Fn::ImportValue': 'WardrobeBackendOriginPrivateDns' });
  assert.equal(backend.ConnectionAttempts, 1);
  assert.equal(backend.ConnectionTimeout, 3);
  assert.equal(backend.CustomOriginConfig, undefined);
  assert.equal(backend.OriginCustomHeaders, undefined);
  const control = config.Origins.find((o: any) => o.Id === 'Control');
  assert.equal(control.CustomOriginConfig.OriginProtocolPolicy, 'https-only');
  assert.ok(control.OriginAccessControlId);
  assert.match(JSON.stringify(control.DomainName), /WardrobeControlFunctionUrl/);
  assert.deepEqual(config.CustomErrorResponses.map((e: any) => e.ErrorCode), [500, 501, 502, 503, 504]);
  for (const error of config.CustomErrorResponses) {
    assert.equal(error.ErrorCachingMinTTL, 0);
    assert.equal(error.ResponseCode, undefined);
    assert.equal(error.ResponsePagePath, undefined);
  }
  const policies = JSON.stringify(t.findResources('AWS::S3::BucketPolicy'));
  assert.match(policies, /cloudfront.amazonaws.com/);
  assert.match(policies, /AWS:SourceArn/);
  assert.match(policies, /distribution\//);
  assert.doesNotMatch(JSON.stringify(t.toJSON()), /origin-verify-secret|PricingPlan|RealtimeLogConfig/);
});
