import { App } from 'aws-cdk-lib';
import { BackendHostStack } from '../lib/backend-host-stack';
import { ControlPlaneStack } from '../lib/control-plane-stack';
import { EdgeHostingStack } from '../lib/edge-hosting-stack';
const app = new App();
const host = new BackendHostStack(app, 'WardrobeBackendHost', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'eu-central-1' },
});
const control = new ControlPlaneStack(app, 'WardrobeControlPlane', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'eu-central-1' },
});
control.addStackDependency(host);
const edge = new EdgeHostingStack(app, 'WardrobeEdgeHosting', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'eu-central-1' },
});
edge.addStackDependency(host);
edge.addStackDependency(control);
