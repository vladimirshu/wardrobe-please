import { App } from 'aws-cdk-lib';
import { BackendHostStack } from '../lib/backend-host-stack';
const app = new App();
new BackendHostStack(app, 'WardrobeBackendHost', {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'eu-central-1' },
});
