# Control-plane verification — 2026-11-24

## Deployment configuration update — 2026-11-24

Owner requested schedule enablement from `infra/deploy.config.json`, default true.
The config and example now include `schedules_enabled: true`. The full deployment
validates a JSON boolean and explicitly supplies `SchedulesEnabled` on every run;
CDK's fresh-stack default is also true. All three schedules use that setting,
independently of `stop_after_deploy`. The binding helper preserves the applied value.
No AWS mutation was performed. Infrastructure compilation, 15 Node tests, 14 Python
tests, IntelliJ build and strict OpenSpec validation passed. Added checks cover
omitted/true/false values on deployment and rejection of string booleans before AWS.

## Implemented locally

- Control Lambda: stopped starts, transition states wait, running probes only the
  configured CloudFront HTTPS health URL with a two-second timeout and no redirects.
  Responses use HTTP 200 UP/STARTING and no-cache headers. Missing/invalid URL fails
  closed before EC2 access; AWS/probe errors log and return STARTING.
- Sweeper: shared validated frontend schedule, zone-aware same-day window,
  off-hours shutdown at >=20 minutes since LaunchTime, five-minute cadence.
- CDK: Node 22 ARM64 functions outside a VPC, bundled SDK, separate roles/log groups,
  instance-scoped mutations, wildcard describe only, AWS_IAM Function URL,
  conditional distribution-scoped invoke grants, schedule-group-scoped trust.
  Three schedules default enabled, controlled by deployment config `schedules_enabled`. Host-first app dependency, no edge reverse reference.
- Edge-binding helper, operator runbook, architecture diagram and related designs.

## Local checks

- `cd infra && npm run build`: passed.
- `cd infra && npm test`: 14 tests passed (four existing host tests, ten control,
  scheduling and sweeper tests). Includes timeout abortion, redirects/503/errors,
  invalid config, repeated calls, 19/20-minute boundary, weekends, both DST changes,
  current cron days and an alternative UTC/weekend configuration.
- `cd infra && python3 -m unittest discover -s test -p 'test_*.py'`: eight passed;
  AWS/Docker calls mocked, no deployment performed.
- `cd infra && AWS_REGION=eu-central-1 AWS_DEFAULT_REGION=eu-central-1
  CDK_DEFAULT_ACCOUNT=708564192046 npm run synth -- --quiet --no-lookups`: passed
  for both stacks using checked-in VPC context; both Lambda bundles built locally.
  An initial synth inherited the host's us-east-1 CLI default and requested a VPC
  lookup; setting the region explicitly resolved it without AWS calls.
- IntelliJ build: successful, with existing Lombok Unsafe deprecation warnings.
- `bash -n infra/bind-control.sh`, strict OpenSpec validation and `git diff --check`: passed.

## Outstanding live evidence

No AWS resources were changed. Do not treat local assertions as deployed behavior.

1. **Task 2.4:** record LaunchTime before/after at least two stop/start cycles. AWS
   documents it as last launch time, but host-specific behavior has not been observed.
   If it does not advance, use the design's one-time-schedule alternative before acceptance.
2. **Task 4.1:** deploy host migration/control and the locally implemented edge distribution,
   bind the Function URL/OAC and readiness URL; verify stopped -> STARTING -> UP,
   repeated calls, direct anonymous URL rejection, EC2 ingress rejection, uncached
   health/5xx, automated probes without WAF/browser challenges and absence of secret/SSM access in deployed roles.
3. **Task 4.2:** after the cost-policy decision, enable schedules and observe a full
   weekday, no weekend recurring start/stop, and a weekend wake stopping in 20–25
   minutes. Keep EC2 stopped outside deliberate acceptance until then.
4. **Task 5.2:** after an operating week, record Cost Explorer Lambda, EventBridge
   Scheduler and EC2 compute actuals against the runbook ceiling, plus actual delivery
   of configured budget alerts. There is no spending or wake cap in this change.

The edge is now implemented locally with owner-approved pay-as-you-go pricing
without WAF (2026-11-24); live CloudFront/readiness acceptance remains pending.
The estimated scheduled-host total fits €10 with little margin, but this does not
establish observed costs or approve routine schedule enablement. Schedule enablement now follows owner-selected deployment config (default true);
live acceptance and observed costs remain unverified.
