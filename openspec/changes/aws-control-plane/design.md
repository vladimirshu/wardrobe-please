## Context

CloudFront can route a path to a Lambda Function URL, but it cannot start or stop the origin. The SPA is client-only: the page loads from S3 whether or not the backend is up, then evaluates the shared opening schedule locally. Inside hours it probes `GET /api/v1/health` and calls control on failure; outside hours it calls control directly on arrival and before each search, avoiding a stopped-origin timeout. During startup it polls control every 3 seconds and opens immediately on a valid UP response, without client-side backend health polling. The owner chose automatic wake on page visit plus a manual "Knock" button, a fixed 20-minute session outside opening hours, and no heartbeat.

Pricing and free-tier statements below are assumptions to verify (Lambda request and duration allowances, EventBridge Scheduler invocation allowance, CloudFront free plan behavior).

## Goals / Non-Goals

**Goals:**
- Start the instance on demand and report readiness, safely callable from every visitor tab.
- Open the instance on weekdays 08:00-22:00 without any visitor.
- Stop an off-hours session after 20–25 minutes, based on launch time and the five-minute sweeper cadence.

**Non-Goals:**
- A per-day wake cap is not implemented by this proposal. The owner set a €10/month target on 2026-11-23; the backend estimate now shows that always-on operation exceeds it after tax/FX. The earlier acceptance of the 24/7 case is superseded. Revisit the wake policy/cost envelope before unattended operation. LLM spend remains separately limited by `demo-cost-controls`.
- Heartbeats, idle detection, or session extension while a visitor is active.
- Visitor and wake-event tracking; basic traffic information uses built-in CloudFront metrics and reports (`aws-edge-hosting`).
- Authentication of visitors.

## Decisions

**1. `/api/v1/control/up` is a Lambda Function URL behind CloudFront.**
Node.js (arm64) Lambda, `AWS_IAM` auth on the Function URL with a CloudFront origin access control, so only the project's distribution can invoke it. No API Gateway (extra cost and a second origin type).
- Response: always HTTP 200 and `{"state":"UP"}` or `{"state":"STARTING"}`; unexpected AWS errors also answer 200 with `STARTING` after logging, because the SPA treats anything but a valid UP control response as "not open" and has its own stuck timeout. The CloudFront behavior uses a caching-disabled policy.

**2. State derived from EC2, no stored state.**
Algorithm per call: describe the instance; `stopped` → `StartInstances` then STARTING; `pending`/`stopping` → STARTING (a stopping instance is started on a later call, since the SPA nudges again every 3 s); `running` → probe the configured `https://<distribution-domain>/api/v1/health` with a 2 s total timeout, without following redirects → 200 gives UP, anything else STARTING. Idempotent by construction (`StartInstances` on a running instance is a no-op). The instance id and `HEALTHCHECK_URL` come from environment configuration. The health URL is deployment-controlled, never taken from a visitor request, and uses the distribution's default HTTPS domain. Lambda runs outside a VPC and needs neither the EC2 public IP nor SSM access to the origin secret. CloudFront uses the private VPC Origin; the EC2 security group admits only its AWS-managed service security group. No origin secret is used. `/api/v1/health` routes to EC2, not the control Lambda, so the probe cannot recurse. Disable caching and 5xx error caching for health; WAF must permit automated probes without browser challenges.

Create the control function and Function URL first, then the distribution, then set `HEALTHCHECK_URL` in a deployment step. Do not introduce a circular infrastructure dependency between the distribution and Lambda configuration. Until configured, control returns STARTING and logs the configuration error.

**3. Schedules in EventBridge Scheduler with an explicit time zone.**
Two recurring schedules call the EC2 `StartInstances`/`StopInstances` APIs through universal targets, so no Lambda is needed for them. The stack reads the checked-in `wardrobe-fe/src/config/opening-hours.json` used by the frontend and configures weekday starts at 08:00 and weekday stops at 22:00 in Europe/Berlin with the current values. EventBridge Scheduler uses the configured IANA zone to follow daylight-saving changes. Deploy schedule changes with a rebuilt frontend. The opening interval is start-inclusive and end-exclusive in that zone. Both schedules use the same configured opening days (currently Monday to Friday). Neither schedule runs on Saturday or Sunday, so weekends are stopped by default. Visitor-triggered weekend wake remains available and the off-hours sweeper stops those sessions; there is no fixed weekend 22:00 stop.
- Alternative: cron rules in EventBridge Rules (UTC only, so DST shifts the window twice a year).

**4. Off-hours session by sweeper, based on instance launch time.**
A second Lambda runs every 5 minutes: if the instance is `running`, now is outside the window, and `now - LaunchTime >= 20 minutes`, stop it. The window parameters (days, from, to, zone) are the same stack constants used for the schedules. `LaunchTime` is assumed to be reset on each stop/start cycle (verify). A wake at 21:50 is stopped by the 22:00 schedule, so its session is shorter than 20 minutes; accepted.
- Alternative: a one-time EventBridge schedule created by `/up` at wake time (exact 20 minutes, but one more write permission and cleanup of schedules; rejected for now).
- Alternative: stop from inside the instance with a cron `shutdown` (no AWS API needed, but the window logic would be duplicated on the host).

**5. No wake-event analytics.**
The control endpoint does not persist invocation or visitor events, accept campaign tags for tracking, or require an analytics table. Basic traffic curiosity is served by built-in CloudFront metrics and reports; they do not count actual EC2 starts. Existing error/configuration logging remains operational logging.

**6. Least privilege.**
Control Lambda: `ec2:DescribeInstances` on `*` (this API has no resource-level permission), `ec2:StartInstances` scoped to the instance ARN. It has no Parameter Store permission and no origin secret. Sweeper Lambda: describe and `StopInstances` on the instance. The Scheduler role may start/stop only this instance.

## Risks / Trade-offs

- [Crawlers or scanners that run JavaScript wake the instance] → Each off-hours session lasts about 20–25 minutes; repeated wakes can approach the 24/7 case, which exceeds the newer €10 target. Auto-wake remains planned; its cost policy needs review.
- [A client repeating `/up` can keep the instance up all night] → The always-on upper bound exceeds the current €10 target; a per-day/runtime cap or revised operating policy needs a separate decision before unattended operation.
- [Cold start of the Lambda adds latency to the first call] → Node.js on arm64 with small bundle; the call only starts a 60-90 s boot, so extra hundreds of milliseconds do not matter.
- [Readiness depends on CloudFront as well as EC2] → This checks the visitor-facing route; edge failures, redirects or probe timeouts return STARTING. One additional CloudFront request per running-instance probe; no new network infrastructure. CloudFront-to-EC2 remains HTTP as documented in `aws-edge-hosting`.
- [Instance state transitions race (stop in progress when the visitor knocks)] → The SPA re-calls `/up` every 3 s; a stopping instance is started by a later call.
- [`StartInstances` can fail with insufficient capacity] → The call logs and answers STARTING; the SPA's 3-minute stuck state tells the visitor.
- [DST edge cases at the window boundary] → Zone-aware schedules; sweeper uses the same zone via the runtime time-zone library.

## Migration Plan

1. Deploy the stack with schedules disabled, invoke `/up` manually against the stopped instance, check STARTING then UP.
2. Enable the schedules; confirm a weekday start at 08:00 and weekday stop at 22:00, and verify neither schedule runs at weekends.
3. Wake off-hours once and confirm the instance stops about 20 minutes later.
4. Rollback: disable the schedules and Lambdas; the instance can be controlled by hand.

## Open Questions

- Shared `infra/` now uses owner-approved CDK TypeScript for the host; control-plane implementation is now present locally; deployment is pending.
- Custom domain: decided later; the control endpoint works on the CloudFront domain.

## Backend integration status — 2026-11-23

The host stack exports `WardrobeBackendInstanceId`; consume only that value here.
`WardrobeBackendVpcOriginId` and `WardrobeBackendOriginPrivateDns` are for the edge
change. The control plane still probes through CloudFront and needs no secret or
origin address. The host is kept stopped after acceptance until scheduling and the
updated cost policy are implemented. See [host verification](../aws-backend-host/verification.md).

## Implementation status — 2026-11-24

Implemented in `infra/lib/control-plane-stack.ts`, `infra/functions/control.ts` and
`infra/functions/sweeper.ts`. Node 22 ARM64, bundled/pinned EC2 SDK, separate roles,
explicit one-week log groups and no VPC attachment. The host/control app dependency
orders first deployment; only the instance-ID export is imported. Describe is
wildcard-scoped because AWS does not support an instance ARN for DescribeInstances.

Function URL uses AWS_IAM. `DistributionId` and `HealthcheckUrl` parameters default
empty; `infra/bind-control.sh` resolves the actual distribution domain and redeploys
only control after edge creation. Conditional permissions grant both required
Lambda invoke actions to that distribution; InvokeFunction is constrained to URL
invocation. There is no reverse CloudFormation reference or out-of-band Lambda
configuration edit. Before valid readiness configuration, control returns STARTING
without describing or starting the instance.

All three Scheduler resources default DISABLED via `SchedulesEnabled=false`, pending
acceptance and the cost-policy decision. Scheduler role trust is scoped to the
account and schedule group (Scheduler supplies the group ARN, not schedule ARN).
Targets use no retries and a 60-second maximum event age to limit delayed starts.
The same validated frontend JSON supplies weekday crons/time zone and the sweeper
environment. Daytime schedules and sweeper stop timing are covered by fixed-clock
unit tests. Same-day windows only are supported; invalid configuration fails synthesis.

AWS documents LaunchTime as the last launch time, supporting the chosen design;
actual stop/start cycle verification is still pending. See [AWS Instance API](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_Instance.html).
CloudFront's two invoke permissions follow the [OAC documentation](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-lambda.html);
Scheduler trust follows [confused-deputy guidance](https://docs.aws.amazon.com/scheduler/latest/UserGuide/cross-service-confused-deputy-prevention.html).
No AWS resources were changed during implementation. Live edge integration depends
on `aws-edge-hosting`; weekday/weekend observation and first-week billing evidence
remain outstanding. See [verification](verification.md).
