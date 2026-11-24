# AWS hosting runbook

## Status and scope

Repository code now uses a CloudFront VPC Origin and AWS-managed source-security-group
access, retaining the EC2 public IP only for outbound internet. **This revision is
not deployed.** The earlier stack in account `708564192046`, eu-central-1, profile
`wardrobe`, used a public custom origin/header scheme. Its instance was stopped at
the owner's wrap-up request. Existing `initial` / `privacy-final` images predate
this revision; build a new immutable tag before deployment.

The backend stack owns the VPC Origin and managed-group lookup; the CloudFront
**distribution** and recurring start/stop are implemented in separate edge/control
stacks, with live deployment still pending. Keep the host
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
`schedules_enabled` defaults to true and controls weekday start/stop plus the
five-minute sweeper. It is independent of `stop_after_deploy`: that setting stops
the host immediately after deployment, whereas enabled schedules act at their next
trigger and do not immediately align EC2 with the opening window.
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

**Scope:** `WardrobeBackendHost`, `WardrobeControlPlane` and `WardrobeEdgeHosting`
are registered, with explicit host → control → edge deployment dependencies. The control schedules follow `schedules_enabled` (default true);
its readiness URL and CloudFront grants remain empty until the edge binding below.
The command also deploys `WardrobeEdgeHosting`, binds control to its distribution
and builds/uploads the SPA. It does not provision external Qdrant Cloud or delete
the historical origin secret. Edge behavior still requires the live checks below.
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
npx cdk deploy --all --profile wardrobe -c budgetEmail=ADDRESS \
  --parameters WardrobeBackendHost:QdrantHost=YOUR_CLUSTER_HOSTNAME --parameters WardrobeBackendHost:ImageTag=YOUR_NEW_IMMUTABLE_TAG \
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
pending edge deployment. Do not claim a particular distribution identity is checked
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

## Control plane (implemented, not yet deployed)

`WardrobeControlPlane` imports only `WardrobeBackendInstanceId`. Its Node 22 ARM64
functions run outside a VPC; they have describe plus instance-scoped start (control)
or stop (sweeper) permission and one-week log retention. DescribeInstances requires
`Resource: *`. No SSM/secret, public-IP or analytics-table permissions are present.
All three Scheduler resources follow `schedules_enabled` in `deploy.config.json`,
defaulting to true when omitted. The wrapper validates a JSON boolean and explicitly
passes `WardrobeControlPlane:SchedulesEnabled` on every deployment, overriding any
previous stack parameter value. Direct fresh CDK deployments also default true.
Set `schedules_enabled: false` for a deliberately disabled deployment.

After deploying the host, deploy control independently if not using the wrapper:

```bash
cd infra
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npx cdk diff WardrobeControlPlane
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npx cdk deploy WardrobeControlPlane --exclusively
```

The edge change must use `WardrobeControlFunctionUrl` as the `/api/v1/control/*`
origin with Lambda OAC (always sign), `AWS_IAM` and caching disabled. It must route
`/api/v1/health` to the backend VPC Origin, disable response and 5xx error caching,
without WAF or browser challenges. Control cannot
confirm UP until that route works. `WardrobeControlFunctionArn` is also exported.
Then, from the repository root:

```bash
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 bash infra/bind-control.sh DISTRIBUTION_ID
```

The helper reads the distribution's actual default domain using AWS CLI, prints a
control-stack diff, and redeploys only control with plain `DistributionId` and
`HealthcheckUrl` parameters. It adds both CloudFront invocation permissions, scoped
to that distribution ARN, and sets `HEALTHCHECK_URL` without a reverse stack
reference. Until bound, there is no CloudFront resource grant and a missing URL
returns HTTP 200 STARTING without starting EC2. Do not configure Lambda directly
with UpdateFunctionConfiguration: the parameters keep configuration in CloudFormation.
Subsequent CDK deployments reuse existing parameter values unless explicitly changed.
Direct anonymous Function URL calls must return 403; trusted administrators retain
their IAM access. No public URL grant is created.

The full deployment applies the config automatically; no separate activation is
needed. To change only the schedules manually (without the full deployment):

```bash
cd infra
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npx cdk deploy WardrobeControlPlane --exclusively --parameters SchedulesEnabled=true
```

Disable all three using the same command with `SchedulesEnabled=false`. This stops
future scheduled invocations; it neither stops EC2 nor disables visitor wake.
For emergency shutdown, remove the edge control behavior/grants or temporarily set
the control function's reserved concurrency to zero, stop the instance manually,
and restore concurrency later with `delete-function-concurrency`. Record emergency
changes and reconcile them with the deployment before resuming normal operation.
Scheduled start/stop targets have no retries and a 60-second maximum event age to
avoid delayed starts beyond a boundary. The sweeper next runs within five minutes.

Manual host control (from any directory):

```bash
aws ec2 start-instances --profile wardrobe --region eu-central-1 --instance-ids INSTANCE_ID
aws ec2 stop-instances --profile wardrobe --region eu-central-1 --instance-ids INSTANCE_ID
```

Edit `wardrobe-fe/src/config/opening-hours.json` to change days, times or zone.
Use unique comma-separated lowercase days, an IANA zone and same-day HH:mm times
with `from < to`. CDK fails synthesis for invalid settings. Rebuild and redeploy
both the frontend and control stack together; the JSON is read at synthesis and
serialized into the sweeper environment. Current start/stop are Mon–Fri 08:00/22:00
Europe/Berlin; the interval includes 08:00 and excludes 22:00. Neither start nor
stop runs at weekends. The sweeper checks every five minutes and stops running
instances outside the window at >=20 minutes since LaunchTime. A weekday 21:50
wake is cut short by the 22:00 stop; a weekend 21:50 wake continues until 22:10–22:15.
There is no heartbeat, session extension, wake cap or spending cap.

Live acceptance remains pending: compare LaunchTime before/after two stop/start
cycles, confirm STARTING then UP through CloudFront, direct-access rejection,
uncached readiness and POST body/header passthrough; observe a weekday and weekend including
a visitor wake. Keep the host stopped outside deliberate checks. After the first
operating week, inspect Lambda, EventBridge Scheduler and EC2 compute usage in
Cost Explorer (exclusive end date), compare with the 24/7 ceiling below, and verify
actual delivery of the host budget's 80%/100% alerts. Estimates or configured alert
thresholds do not prove delivery. Record evidence in the control-plane verification file.

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
edge/control-plane operations are additional. Allow
$0.50/month operational/transfer headroom for this small prototype and verify
actuals; heavy traffic is not covered by that allowance. OpenAI/Qdrant charges
are outside the AWS budget. Tax/FX and other account resources can consume the
remaining €10 headroom. Do not operate continuously or enable small on this estimate.

After the first full operating week, query Cost Explorer by service and usage type
for EC2 compute, EBS, IPv4, ECR and transfer (exclusive end date), and record actual
figures here. This future check is not yet complete and is not automatically scheduled.


## Edge hosting (implemented locally, not deployed)

The owner approved pay-as-you-go CloudFront **without WAF** on 2026-11-24.
Do not enroll this distribution in the Free flat-rate plan (it excludes VPC
Origins), attach a web ACL, or enable paid Shield/logs/metrics during setup.
Included Shield Standard, private origins, S3/Lambda OAC and backend search limits
remain. There is no application-layer edge filtering or wake spending cap.
The scheduled-host estimate plus small operational/S3 allowances is about
€9/month including illustrative VAT; see [pricing evaluation](../openspec/changes/archive/2026-11-24-aws-edge-hosting/verification.md).
Account/organization allowance availability and actual costs are unverified.

### Provision and publish

The full `bash infra/deploy.sh` workflow deploys all three stacks, activates the
host, binds control and publishes Vite. It stops EC2 on success or ordinary failure
by default; all three schedules follow `schedules_enabled` (default true).
Schedules can run during deployment when enabled. For maintenance requiring no
scheduled actions, explicitly deploy with the setting false, then redeploy true
when ready. Frontend build failure
leaves the prior release available; first deployment may have an empty SPA bucket
until publication succeeds. Cross-service deployment is not atomic.

For independent edge provisioning after the host/control exports exist, keep EC2
running during VPC Origin creation/association, then from `infra/`:

```bash
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npm run build
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npx cdk diff WardrobeEdgeHosting
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 npx cdk deploy WardrobeEdgeHosting --exclusively
```

Read `DistributionId`, `DistributionDomain` and `SpaBucketName` from the stack
outputs. Bind and publish from the repository root:

```bash
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 bash infra/bind-control.sh DISTRIBUTION_ID
AWS_PROFILE=wardrobe AWS_REGION=eu-central-1 bash infra/deploy-spa.sh
```

The bind helper prints a diff and deploys only control without an interactive CDK
approval prompt; it supplies plain distribution/health parameters, not stack
references. Lambda has both distribution-scoped invocation grants and AWS_IAM auth.
The edge's explicit Lambda OAC always signs its HTTPS Function URL requests.

The SPA helper resolves destinations from `WardrobeEdgeHosting` outputs, installs
locked frontend dependencies and builds. It uploads `assets/` with one-year
immutable caching, other files with 60 seconds, then index last with 60 seconds.
It invalidates `/index.html` and `/` (two paths per release). Invalidation creation
is asynchronous: wait for completion in CloudFront before checking propagation.
It never deletes old hashed assets, allowing open tabs and rollback; deliberately
clean obsolete builds later. Node 22+, Python 3.9+ and AWS CLI are required.
No API host configuration or CORS change is needed; the storefront uses relative paths.

### Inspect origins and accept the deployment

```bash
aws cloudfront get-distribution-config --id DISTRIBUTION_ID --profile wardrobe
aws cloudfront get-vpc-origin --id VPC_ORIGIN_ID --profile wardrobe
aws ec2 describe-security-groups --group-ids HOST_SG_ID --profile wardrobe --region eu-central-1
```

Confirm the distribution has three origins; `/api/v1/control/*` comes before
`/api/v1/*`. Control has Lambda OAC, HTTPS and caching disabled. Backend references
`WardrobeBackendVpcOriginId` and private DNS, one connection attempt, three-second
connection timeout and 30-second response timeout. The host-owned VPC Origin
specifies HTTP :8080; no shared header, second VPC Origin or public backend
HttpOrigin exists. Inspect the service-managed SG reference: the host admits TCP
8080 only from `CloudFront-VPCOrigins-Service-SG`. Do not share via AWS RAM.

Both API behaviors use CachingDisabled and AllViewerExceptHostHeader, preserving
Content-Type, request body, query strings and cookies while setting the origin
Host. Control supports GET/HEAD; backend permits POST. Managed SecurityHeadersPolicy
is on the static behavior. Static caching uses `CachingOptimized` without an origin
request policy: do not forward the viewer Host to S3 (for example via
`UseOriginCacheControlHeaders`), because S3 uses Host for bucket routing. The
policy’s one-second minimum is below the deployed 60-second/one-year cache headers. Zero error TTLs cover 500/501/502/503/504 distribution-wide,
not individual paths, and do not replace statuses/bodies. S3 errors have AWS's
one-second floor. Unknown static paths return S3 errors; there is no SPA fallback
that could swallow an API failure. Missing control binding fails closed.

Record these live checks in the edge verification file (not yet performed):

1. With EC2 stopped, repeated HTTPS `/api/v1/health` calls return fast 5xx in about
   three seconds, without stale cached errors. Frontend probes may abort earlier
   at their existing 2.5-second timeout; Lambda probes have their separate two seconds.
2. `/api/v1/control/up` yields STARTING, starts the host, then yields UP only once
   its CloudFront health probe succeeds. Confirm no recursion or cached 504 after wake.
3. POST normal search JSON with `Content-Type: application/json` through the
   distribution. Confirm body semantics, uncached responses and unchanged backend
   errors/429. Do not enable request-body logging to inspect payloads. Verify the
   25-second backend deadline can return within the browser's 30-second window under
   normal conditions; CloudFront timeouts alone are not an end-to-end guarantee.
4. Anonymous direct Function URL and S3 `/index.html` calls return 403; public EC2
   :8080 access times out. IAM administrators retain their authorized access.
5. Load the SPA on its default `*.cloudfront.net` name, inspect security/cache
   headers, and confirm no tracking scripts, WAF association or analytics resources.
6. Stop EC2 after deliberate checks; observe scheduled behavior separately before
   enabling any schedules for routine operation.

### Monitoring, costs and shutdown

Use CloudFront console → Distributions → distribution → **Monitoring** for default
requests, bytes and aggregate error-rate metrics. Console **Reports & analytics**
provides available usage, popular objects, referrers and viewer reports. Use only
reports available without enabling paid features. Data can be delayed; requests
include assets, bots and readiness/control probes, not unique human visits or
sessions. Missing product-analytics detail does not justify adding a log pipeline.
No access-log bucket/delivery, tracking code, custom dashboard or paid metrics are
configured. Check deployed configuration and traffic after data arrives.

Before operating, confirm the `wardrobe-monthly` budget and notification recipient
exist, and inspect remaining recurring CloudFront allowance across the account /
organization. The budget is an alert, not a cap. After the first week, review Cost
Explorer service/usage lines for CloudFront, S3, Lambda, Scheduler, EC2 and WAF
(expected absent/zero for this distribution); record actual figures. POST transfer
toward origins and S3 requests/storage are separate from free delivery allowances.
No custom domain/Route 53 charge or subscription is assumed in the estimate.

To disable public delivery, console → distribution → **Disable**, or save the
current `get-distribution-config` response, set only `DistributionConfig.Enabled`
to false in a config JSON file, then use `update-distribution --id ID --if-match ETAG
--distribution-config file://CONFIG.json`. Wait for deployment. This manual emergency
change creates drift; reconcile before the next CDK deploy. Disabling CloudFront
neither stops EC2 nor disables schedules: disable schedules and stop EC2 separately.
For immediate wake suppression use the control emergency procedure above.

Rollback a release by rerunning the SPA helper against the known-good frontend
checkout; it builds that checkout and publishes index last. The bucket is retained
on stack deletion and has no auto-delete Lambda. Retained objects cost storage
until deliberately deleted. Never delete the bucket before disabling/removing its
CloudFront association. Optional custom-domain ACM (us-east-1)/DNS and origin TLS
remain deferred until explicitly selected; HTTPS origin also requires updating the
host's VPC Origin protocol/port and SG, not just the distribution.


### Static XML NotFound with existing S3 objects

If both `/` and `/index.html` return XML NotFound while the bucket contains
`index.html`, inspect the default behavior's cache policy. `UseOriginCacheControlHeaders`
includes the viewer Host and is unsuitable for this S3 behavior. Repository code
now selects managed `CachingOptimized` with no origin request policy. Deploy the
edge stack update, wait for propagation, then invalidate `/` and `/index.html`
(or publish through the SPA helper, which creates those invalidations). Uploading
SPA files alone does not update the distribution policy. Recheck the page and
its referenced assets after invalidation completes; no public bucket access is needed.
