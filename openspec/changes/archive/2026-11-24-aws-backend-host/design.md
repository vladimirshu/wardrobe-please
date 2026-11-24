## Context

Current state of the repo:
- `wardrobe-be/Dockerfile` builds the executable jar with the Temurin 25 LTS JDK and runs it on the Temurin 25 JRE. The build supports arm64 and the runtime user is non-root.
- Full request/response logging and `SimpleLoggerAdvisor` have already been removed. Existing configuration suppresses Spring AI loggers that can expose payloads. Preserve these restrictions when adding the AWS INFO profile.
- `docker-compose.yml` already starts the backend, frontend and Qdrant for local development; MySQL is removed. Qdrant Cloud TLS/API-key configuration and `/api/v1/health` readiness already exist. The AWS host needs only the application container.
- `SecurityConfig` permits everything, so the network path is the only protection of the origin.

All pricing and free-tier statements below are assumptions to verify in the AWS pricing calculator before the first deploy.

## Goals / Non-Goals

**Goals:**
- One reproducible EC2 host that runs the backend container unattended after every start.
- Origin reachable only through the project's own CloudFront distribution.
- No secret in the image, the user data, the repository or the logs.
- Disk and memory behavior evaluated on a 1 GiB instance, with a larger-instance fallback.

**Non-Goals:**
- High availability, autoscaling, blue/green deploys (single instance, a restart on deploy is acceptable).
- Qdrant on the host (free Qdrant Cloud tier is used).
- Starting/stopping the instance (`aws-control-plane`) and CloudFront/S3 (`aws-edge-hosting`).
- Origin TLS and a custom domain; the backend HTTP hop stays inside the VPC-origin private path.

## Decisions

**1. EC2 `t4g.micro` evaluation candidate with Elastic IP.**
Start with a 1 GiB host, a 640 MiB backend container and the shared
`-Xms64m -Xmx256m` JVM limits. This leaves a nominal 384 MiB for OS/Docker/SSM;
verify actual headroom, startup/reindex and real-provider search latency before
accepting the host. Use `t4g.small` (2 GiB) if needed. Qdrant remains remote.
The failed nano experiment and its alternate Compose profile are retired.

The container uses JVM-default GC and metaspace sizing, with no swap access
(`--memory=640m --memory-swap=640m`). Retain the planned 1 GiB emergency host swap
file, but do not count it as workload capacity. The Elastic IP provides outbound internet access across stop/start; CloudFront uses the
private interface instead. The earlier public-origin deployment is historical; VPC-origin
deployment and host-capacity acceptance are not claimed.

**2. Container from ECR, supervised by systemd.**
A private ECR repository holds the arm64 image (lifecycle policy: keep the last 3 images). A systemd unit runs `docker run` after `docker.service` and `network-online.target`, restarting on failure. Deploy = push a new tag, then an SSM Run Command pulls it and restarts the unit.
- Alternative: copy the jar and run it directly (no image to maintain, but no portable artifact and no reuse of the existing `Dockerfile`).
- Alternative: public registry (zero cost, but images of a private prototype become public).

**3. Multi-stage `Dockerfile` on a maintained base.**
Build stage: a JDK 25 LTS image (Eclipse Temurin); runtime stage: a JRE-only Temurin 25 image, non-root user, the image entrypoint loading shared `runtime-jvm.args` (`-Xms64m -Xmx256m`), with no collector/metaspace overrides from the host unit. The build runs in CI/on the developer machine with `docker buildx --platform linux/arm64`, not on the instance.

The [Java 25 upgrade](../../upgrade-backend-java-25/verification.md) aligns the repository toolchain and container runtime. Its local/container checks do not validate these planned host JVM flags. Recheck Linux host/native memory and swap on the selected instance; the earlier 192 MiB heap remains historical measurement evidence. Retain the prior Java 17 artifact/image as a complete rollback unit.

**4. Secrets in SSM Parameter Store SecureString.**
SecureString parameters under `/ecom/prod/`: `openai-api-key`, `qdrant-api-key`. Supply the non-secret Qdrant hostname through deployment configuration; it may appear in existing connection diagnostics. The instance role may call `ssm:GetParameters` on those two exact parameter ARNs only. A small start script (`ExecStartPre`) combines the fetched secrets with deployment configuration in the container environment (`OPENAI_API_KEY`, `QDRANT_HOST`, `QDRANT_API_KEY`, `QDRANT_USE_TLS=true`). Standard-tier parameters are assumed free; verify.
- Alternative: Secrets Manager (a per-secret monthly fee, which is not justified for these credentials).

**5. `aws` Spring profile and log hygiene.**
The `aws` name denotes an ordinary Spring Boot deployment configuration, not an AWS login/IAM profile, log collector or CloudWatch integration. Activate `application-aws.yml` with `SPRING_PROFILES_ACTIVE=aws` in the host service. Keep the existing local defaults; no separate logging service is introduced.
`application-aws.yml` sets root and `com.ecomassistant` to INFO and retains `org.springframework.ai` at INFO. Preserve the existing ERROR overrides for `SpringAiRetryAutoConfiguration`, `DeepSeekChatModel` and `OpenAiChatModel`, which can otherwise log payloads. No advisor removal or JPA/SQL cleanup remains. Preserve the existing health-only Actuator exposure and verify the effective profile on normal and provider-error paths. ERROR overrides suppress known WARN payload leaks but are not by themselves proof that every ERROR path is safe; verify empty/malformed model responses and provider exceptions without logging request bodies, raw responses or unsanitised exception payloads. Docker uses the `local` or `journald` driver with a size cap (for example 10 MB x 3 files) so the 8 GB volume cannot fill up.

**6. CloudFront VPC Origin and service-managed security group.**
`BackendHostStack` creates `AWS::CloudFront::VpcOrigin` for the EC2 private interface
on HTTP 8080. After it is deployed, an AWS SDK custom resource discovers
`CloudFront-VPCOrigins-Service-SG` using VPC ID and group name. A separate ingress
resource allows only this source group; ordering avoids a security-group/EC2/origin
circular dependency. No temporary shared prefix-list rule is introduced. AWS owns
the service group: the stack never creates or edits it. The lookup role has only
`ec2:DescribeSecurityGroups` (an action that cannot be resource-scoped).

The host exports `WardrobeBackendVpcOriginId` and `WardrobeBackendOriginPrivateDns`.
The separate edge change must bind its backend behavior to that VPC Origin, not
create a public `HttpOrigin`. Do not share the VPC Origin via AWS RAM. Administrative
IAM access to origin association and security-group changes remains trusted.

Retain the public subnet, Internet Gateway default route and Elastic IP for outgoing
IPv4 requests. Stateful security groups permit their replies without opening inbound
ports. VPC Origins uses AWS-managed ENIs and the private IP, not that public route.
No NAT Gateway is needed. No application filter, secret header or SSM origin secret
is needed for this boundary; local requests use ordinary application behavior.

AWS references: [VPC Origins](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html),
[CDK EC2 VPC origin](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_cloudfront_origins-readme.html),
[Internet Gateway](https://docs.aws.amazon.com/vpc/latest/userguide/VPC_Internet_Gateway.html).

**7. Operations without SSH.**
SSM Session Manager (instance role `AmazonSSMManagedInstanceCore`) replaces SSH; port 22 is never opened. The instance has a public IPv4 (the Elastic IP) and reaches SSM, ECR, SSM Parameter Store, OpenAI and Qdrant Cloud over the internet, so no NAT gateway is needed.

**8. Readiness before traffic.**
The existing `GET /api/v1/health` endpoint reports readiness after catalog startup completes and while Qdrant is reachable. A running systemd unit alone does not prove readiness. Host-local verification needs no special header; CloudFront probes use the VPC Origin. The control-plane Lambda and SPA use the endpoint for readiness; no new host-side health reporting mechanism is required.

## Risks / Trade-offs

- [1 GiB may be insufficient] → 256 MiB heap and 640 MiB container cap, default GC, no backend swap. Check total host memory/startup/latency; use small if needed. Host swap is emergency capacity, not normal workload capacity.
- [Public IP retained for egress] → Admit only the VPC-origin service security group on 8080; no public CIDR or shared CloudFront prefix-list ingress.
- [HTTP on the backend hop] → This hop uses the VPC-origin private path. Viewer access stays HTTPS; origin TLS remains optional future work.
- [Secrets visible in `docker inspect` or process environment on the host] → Access to the host is through SSM only, limited to the owner's IAM identity; no secrets in logs.
- [Elastic IP is billed while the instance is stopped] → Assumed about $3.65/month (public IPv4 pricing, verify); retained for outbound internet connectivity without NAT Gateway.
- [Free-tier or credits may not cover the selected instance] → Cost check task after the first week.

## Migration Plan

1. Keep the old instance stopped while preparing the migration. Review `cdk diff`
   for replacements/exports; preserve the existing EIP and application configuration.
2. Deploy network changes before activating a filter-free image: remove all shared
   CloudFront prefix-list/public ingress, start EC2 as needed to create the VPC Origin,
   discover AWS's service group and install only its 8080 ingress rule. Keep the old
   application's filter during this infrastructure phase; temporary unavailability
   is acceptable for this single-instance prototype.
3. Build/push a new immutable image without the filter. Update the existing host's
   prepare script (cloud-init does not rerun on user-data updates), image tag and
   checked-in service configuration through SSM; restart and check local readiness
   without a header. The prepare script now reads only the two provider keys.
4. Bind the edge backend behavior to the exported VPC Origin/private DNS. Verify
   health and POST search through CloudFront without custom authentication headers;
   verify direct public and foreign-distribution access fail, outbound providers work,
   and stop/start preserves the VPC-origin path. Edge deployment remains separate.
5. Only after no old deployment consumes it, remove the obsolete SSM origin secret
   out of band. No secret value or removal is part of this local repository change.
6. Roll back an image only while keeping the managed-SG-only network restriction;
   an old image with its filter still enabled also needs its old secret. Never restore
   broad CloudFront prefix-list ingress while running a filter-free image.

## Open Questions

- Resolved 2026-11-23: owner approved AWS CDK TypeScript; implemented under `infra/`.
- Resolved 2026-11-23: eu-central-1; owner specified €10/month. Budget alerts use an early $8 USD threshold (AWS reporting currency), with tax/FX headroom; this is not a hard cap. Always-on operation exceeds the estimate. A future CloudFront viewer certificate must be in us-east-1.
- Whether micro is acceptable after total-host validation, or small is needed.
- Custom domain: decided later; this change must work without it.


## Implementation notes — 2026-11-23

- Deployment convenience (2026-11-24): `bash infra/deploy.sh` loads ignored JSON
  configuration and deploys all implemented CDK stacks through CloudFormation.
  Its helper checks the account/secret metadata, builds ARM64 locally, closes old
  stack-owned broad ingress before starting an existing host, checks the final
  managed-SG-only rule, pushes an immutable ECR tag, updates the prepare script,
  systemd unit and non-secret host settings via SSM, and checks local readiness.
  It stops the host on completion/ordinary failure by default; scheduling and public
  edge verification remain separate. Local mocked coverage does not establish live
  acceptance. See the runbook for configuration and recovery limitations.

- Host code and operations: [`infra/`](../../../../infra/RUNBOOK.md); acceptance evidence
  is tracked in [`verification.md`](verification.md), separately from code readiness.
- Standard CPU credits avoid T4g unlimited surplus-credit charges; throttling/latency
  are part of host acceptance. IMDS hop limit 1 keeps AWS credential access on the host;
  the application container has no AWS API responsibilities.
- The SSM managed baseline grants broad parameter reads. Explicit deny statements
  override those reads outside the two exact application parameter ARNs.
- Standard SecureStrings are provisioned out of band, not as CloudFormation values.
  There is no origin-verification secret in the new configuration.
- Runtime secrets and registry login state use root-only `/run` files. The prepare
  script suppresses raw AWS errors; Docker authentication state is removed after pull.
- Docker uses the local driver capped at 10 MiB × 3; journald is capped separately.
  Old local image caches still require pruning, preserving a rollback image.
- The obsolete origin filter, property, tests and environment-variable injection are removed.
- Budget alerts cover the account, including resources without activated allocation
  tags. Scheduled operation is estimated at $7.6684/month before tax and extra usage;
  always-on at $11.7196. Leave the host stopped until control-plane scheduling exists.

- Expanded logging tests exposed unsanitized refinement provider exceptions; the advisor
  now converts provider failures to a generic assistant failure without a raw cause.
  Empty/malformed/schema-invalid/authentication/transient paths and unknown-SKU output
  are covered for query analysis and refinement.

- The global exception handler also sanitizes OpenAI/HTTP provider failures from
  embedding/retrieval; these do not pass through the chat advisors.
