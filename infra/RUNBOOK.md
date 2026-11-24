# Backend host runbook

## Status and scope

Repository code now uses a CloudFront VPC Origin and AWS-managed source-security-group
access, retaining the EC2 public IP only for outbound internet. **This revision is
not deployed.** The earlier stack in account `708564192046`, eu-central-1, profile
`wardrobe`, used a public custom origin/header scheme. Its instance was stopped at
the owner's wrap-up request. Existing `initial` / `privacy-final` images predate
this revision; build a new immutable tag before deployment.

The backend stack owns the VPC Origin and managed-group lookup; the CloudFront
**distribution** and recurring start/stop remain separate changes. Keep the host
stopped outside deliberate migration/acceptance until its operating hours are
controlled; always-on operation does not fit the €10/month target.

## Migration from the public-origin/header deployment

1. Review `cdk diff` and exports before any mutation. Retain the public subnet,
   Internet Gateway default route and EIP for outbound access. Do not add NAT Gateway.
2. With the old application/filter still in place, remove the broad CloudFront
   prefix-list/public inbound rule. Start EC2 as required to create its VPC Origin.
   AWS creates `CloudFront-VPCOrigins-Service-SG`; the SDK custom resource discovers
   it within the VPC, and only its source-group TCP 8080 rule is installed. No temporary
   prefix-list rule is required. Origin creation can take up to 15 minutes. This
   SDK lookup runs only on provisioning/update and does not modify the managed group.
3. Build/push a new filter-free arm64 image tag. Through SSM, install the current
   `scripts/prepare.py` as root-owned mode 0700 `/usr/local/sbin/wardrobe-prepare`,
   preserve `/etc/wardrobe/config.json` and update its image tag, then restart the
   service. User-data updates alone do not rewrite files on an existing instance.
   The new prepare script reads only the two provider keys and no origin secret.
4. In the edge change, import `WardrobeBackendVpcOriginId` and
   `WardrobeBackendOriginPrivateDns`, then bind with
   `origins.VpcOrigin.withVpcOrigin`. Do not use the public IP/DNS as `HttpOrigin`,
   add custom authentication headers, or share this origin with other AWS accounts.
5. Verify local/CloudFront readiness and POST search without origin headers, blocked
   direct public access and foreign CloudFront access, outgoing API calls, crash
   recovery and EC2 stop/start preserving its private-origin path. Record results.
6. Only after old consumers are gone, remove `/ecom/prod/origin-verify-secret` out of
   band. No parameter deletion or deployment is performed by this repository edit.

Keep managed-SG-only ingress during rollback. A historical image with its filter
active also needs its historical secret; never reopen shared CloudFront prefix-list
access while the filter is absent/disabled. Temporary unavailability is acceptable.

## Prerequisites and first deployment

### One-command deployment (recommended)

From the repository root, run `bash infra/deploy.sh`. On its first run it creates
ignored `infra/deploy.config.json` and exits before contacting AWS. Set
`budget_email` in that file, then run the same command again. Every executable
line in the short entry script has an explanatory comment; AWS/SSM plumbing lives
in `scripts/deploy.py`. An alternative config path can be passed as the first argument
(relative paths are resolved from `infra/`). Only run one deployment at a time.

Defaults: profile `wardrobe`, account `708564192046`, region `eu-central-1`, budget
$8 USD/month, and stop EC2 after verification. Empty `qdrant_host` reuses the
existing stack's `QdrantHost` parameter; for a new stack, set the DNS hostname
without scheme/path. `stop_after_deploy: false` explicitly leaves the service
running; without scheduling, continuous operation exceeds the current budget estimate.
No API keys belong in this file. The existing two SecureStrings remain prerequisites.

Install Node 22+, Python 3.9+, AWS CLI v2, and Docker with buildx; start Docker and
authenticate the AWS profile before running. Java 25 is provided by the Docker build.
The script checks the AWS account and secret metadata without reading secret values,
runs local CDK checks and builds a unique ARM64 image before starting EC2. It bootstraps
CDK, prints a diff and deploys all stacks registered in `bin/app.ts` without an
interactive approval prompt. Read the diff in the output; use the manual commands
below if you want to review it before deployment.

For migration it removes public/prefix-list ingress only from the stack-owned host
SG before starting the old host. CloudFormation then creates the VPC Origin and
managed-SG rule. The script checks that the resulting host has exactly that one
8080 inbound rule before uploading/activating the new image. It uses SSM to install
the current prepare script/systemd unit, preserve unrelated host config fields,
update image/Qdrant settings, restart and verify local readiness. SSM commands carry
no secret values. It waits for EC2 to stop on success or failure by default (normal
error/interrupt handling; forced termination of the process cannot guarantee cleanup).
Deployment is not atomic across CloudFormation, ECR and SSM; on failure inspect the
reported phase/SSM command and rerun after fixing the cause. Automatic image rollback
and host Docker-cache pruning are not provided.

**Scope:** currently only `WardrobeBackendHost` is registered. This command does not
implement/deploy the planned storefront/CloudFront distribution, control plane or
external Qdrant Cloud cluster, and does not delete the historical origin secret.
Readiness verifies the host locally, not public HTTPS/search behavior. The wrapper
has local mocked coverage; live migration/acceptance remains pending.

### Manual deployment

Use Node 22+, AWS CLI v2, Docker/buildx and the repository Java 25 toolchain.

```bash
aws sts get-caller-identity --profile wardrobe
cd infra
npm ci
npm run build
npm test
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npm run synth
```

`cdk.context.json` records the default VPC/public subnets for this account/region.
Clear/relookup the relevant context if the VPC changes; do not copy these IDs into another account.

Manage the two API keys manually in AWS Console → Systems Manager → Parameter Store
in `eu-central-1`. The owner confirmed that the required values already exist;
do not recreate them during deployment. For a fresh account, create:

- `/ecom/prod/openai-api-key`
- `/ecom/prod/qdrant-api-key`

Use the Standard tier, type `SecureString`, and the default SSM KMS key. To rotate
a key, edit its value in the console, then run `sudo systemctl restart wardrobe-backend`
on the host through Session Manager. The startup script reads the updated values
before creating the container.

Secret values must never be supplied via CDK context, user data, Git, command-line
arguments or SSM Run Command bodies. Customer-managed KMS keys require additional
scoped `kms:Decrypt` permission; they are not configured here.

Before deploying, configure a budget recipient with `-c budgetEmail=ADDRESS`.
AWS Budgets reports USD; the default alert limit is a conservative $8/month,
with actual 80%/100% alerts. Set `-c budgetUsd=AMOUNT` only after checking the
€10 target against exchange rate and applicable tax. Alerts do not cap spending.
The budget covers this AWS account, including untagged EIP/EBS and future edge
resources; unrelated account charges also count. There is no budget report fee
or automated budget action.

```bash
npx cdk bootstrap aws://708564192046/eu-central-1 --profile wardrobe
npx cdk diff --profile wardrobe -c budgetEmail=ADDRESS
npx cdk deploy --profile wardrobe -c budgetEmail=ADDRESS \
  --parameters QdrantHost=YOUR_CLUSTER_HOSTNAME --parameters ImageTag=YOUR_NEW_IMMUTABLE_TAG \
  --outputs-file outputs.json
```

For a fresh stack, the first service start retries until its configured tag is pushed.
The instance must be running for VPC Origin creation; the distribution is bound later. Build from the repo
root; Docker's build context contains source/config only and receives no secrets:

```bash
docker buildx build --platform linux/arm64 --load -t wardrobe-backend:initial wardrobe-be
```

Read `RepositoryUri` and `InstanceId` from `outputs.json`. Authenticate to its
registry using `aws ecr get-login-password --profile wardrobe --region eu-central-1`
piped into `docker login --username AWS --password-stdin REGISTRY`, then:

```bash
docker tag wardrobe-backend:initial REPOSITORY_URI:initial
docker push REPOSITORY_URI:initial
aws ssm start-session --profile wardrobe --region eu-central-1 --target INSTANCE_ID
```

No inbound SSH is available. If a new host is initially absent from SSM, wait for
Elastic IP association and cloud-init installation before diagnosing the agent.
Cloud-init waits for outbound connectivity before installing Docker. Its contents
include configuration/parameter names but no parameter values.

## Deploy and rollback

Push a unique immutable image tag for every release. Keep the prior tag/digest
before pushing: ECR retains only three images, so older rollback images may expire.
On the host (Session Manager), change only `tag` in `/etc/wardrobe/config.json`
using Python/JSON, then run `systemctl restart wardrobe-backend`. This config
persists across stop/start. Update the CDK `ImageTag` parameter as well before any
future instance replacement; EC2 user-data changes do not reconfigure a running host.
Rollback changes `tag` to the prior retained version and restarts the same service.
Retain the separate historical Java 17 artifact/image if a runtime rollback is needed.

For unattended deployment, use `aws ssm send-command --document-name
AWS-RunShellScript --instance-ids INSTANCE_ID --parameters file://deploy-command.json
--profile wardrobe --region eu-central-1`. The JSON must contain only commands to
update the non-secret image tag and restart the service, never credentials. Poll
`aws ssm get-command-invocation` and then verify readiness; a successful command
or running unit does not prove catalog readiness.

## Readiness and acceptance

The deployment wrapper checks host-local readiness automatically. The owner performs
the broader demo acceptance below manually; the wrapper does not establish it.
Request `http://127.0.0.1:8080/api/v1/health` from the host without custom headers;
expect 200 and `{"status":"UP"}` after startup. No application-level origin 403 is
expected. Public `OriginIp:8080` must time out, including when used as another
account's public CloudFront origin. Confirm the host SG has exactly one TCP 8080
source-group rule and no public CIDR/shared CloudFront prefix-list rule.

Edge consumes exports `WardrobeBackendVpcOriginId` and `WardrobeBackendOriginPrivateDns`;
control consumes `WardrobeBackendInstanceId` only. The public IP is for outbound
internet only; the CloudFront hop uses private connectivity. The VPC Origin must
not be shared via AWS RAM. Readiness and POST checks through the distribution remain
pending the edge change. Do not claim a particular distribution identity is checked
by the application: this boundary is enforced by VPC-origin association, trusted IAM
administration and the service-group-only rule.

Use the demo's normal search flow for manual functional verification. No additional
automated load or cold-reindex checks are required for this demo. Micro capacity
has not been established by a load test. Switch `InstanceType` to `t4g.small` only
after revisiting the budget.

Stop the host after checks until the control-plane operating schedule is deployed:

```bash
aws ec2 stop-instances --profile wardrobe --region eu-central-1 --instance-ids INSTANCE_ID
```

## Logs, secrets and host replacement

Use `journalctl -u wardrobe-backend --since '10 minutes ago'` and
`docker logs --since 10m wardrobe-backend`. Do not enable debug/wire logging.
Docker local logs cap each container at 10 MiB × 3 files; host journal limits are
50 MiB persistent / 20 MiB runtime. Also monitor `df -h` and `docker system df`:
images, package caches and cloud-init logs are separate from container logs.
Remove obsolete local image IDs after deployment, preserving the active and prior
rollback image; ECR lifecycle does not prune the host's Docker cache.

The environment file is root-only in `/run` (tmpfs), removed when the service
stops, and recreated on each start. Docker root/SSM administrators can inspect
container environment; that access is privileged. Registry login state is also
kept under `/run` and removed after each pull. The instance uses IMDSv2 with a
host-only hop limit; the backend has no AWS API responsibilities.

Rotate a SecureString through the AWS console without pasting it into a Run
Command, then restart the service. Only the OpenAI and Qdrant keys remain. API-key rotation needs no image rebuild.

Review `cdk diff` before replacement. The EIP is separate from the host, so it
can reassociate with a replacement; no catalog or application state lives on EBS.
Secret reads are restricted by exact ARN, including an explicit deny overriding
the SSM managed policy's broad parameter-read permissions. ECR is retained on
stack deletion; delete retained images/repository deliberately only when retired.

## Monthly cost estimate — 2026-11-23

Official Price List API lookup: Frankfurt Linux shared-tenancy `t4g.micro`
$0.0096/hour; gp3 $0.0952/GB-month; private ECR $0.10/GB-month.
[VPC pricing](https://aws.amazon.com/vpc/pricing/) lists public IPv4 at $0.005/hour,
including retained idle addresses. No free-tier eligibility or credits are assumed.

| Item | Monthly assumption | USD before tax |
|---|---|---:|
| EC2 | 308 hours (22 weekdays × 14 hours) | 2.9568 |
| gp3 | 8 GB, baseline IOPS/throughput | 0.7616 |
| Elastic IP | 730 hours, including stopped periods | 3.6500 |
| ECR | 3 GB stored (conservative image allowance) | 0.3000 |
| Baseline total | Scheduled operation | **7.6684** |
| Baseline total | 730-hour always-on operation | **11.7196** |
| Baseline total | Host stopped all month | **4.7116** |

These are estimates, not spending caps. Recalculate for the actual month's hours.
Standard SecureStrings, ordinary Session Manager, and budget alerts have no
assumed standing fee; API/KMS usage, outbound transfer, provider API calls, CDK
bootstrap storage, the provisioning-time SDK lookup Lambda/API calls, and future
edge/control-plane resources are additional. Allow
$0.50/month operational/transfer headroom for this small prototype and verify
actuals; heavy traffic is not covered by that allowance. OpenAI/Qdrant charges
are outside the AWS budget. Tax/FX and other account resources can consume the
remaining €10 headroom. Do not operate continuously or enable small on this estimate.

After the first full operating week, query Cost Explorer by service and usage type
for EC2 compute, EBS, IPv4, ECR and transfer (exclusive end date), and record actual
figures here. This future check is not yet complete and is not automatically scheduled.
