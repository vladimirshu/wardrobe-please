## Context

Current state of the repo:
- `wardrobe-be/Dockerfile` builds the executable jar with the Temurin 25 LTS JDK and runs it on the Temurin 25 JRE. The deployment build still needs an explicit arm64 target and a non-root runtime user.
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
- HTTPS to the origin (no domain yet; see `aws-edge-hosting` for the compensating controls).

## Decisions

**1. EC2 `t4g.micro` evaluation candidate with Elastic IP.**
Start with a 1 GiB host, a 640 MiB backend container and the shared
`-Xms64m -Xmx256m` JVM limits. This leaves a nominal 384 MiB for OS/Docker/SSM;
verify actual headroom, startup/reindex and real-provider search latency before
accepting the host. Use `t4g.small` (2 GiB) if needed. Qdrant remains remote.
The failed nano experiment and its alternate Compose profile are retired.

The container uses JVM-default GC and metaspace sizing, with no swap access
(`--memory=640m --memory-swap=640m`). Retain the planned 1 GiB emergency host swap
file, but do not count it as workload capacity. The Elastic IP provides a stable
origin across stop/start. No AWS deployment or host-capacity acceptance is claimed.

**2. Container from ECR, supervised by systemd.**
A private ECR repository holds the arm64 image (lifecycle policy: keep the last 3 images). A systemd unit runs `docker run` after `docker.service` and `network-online.target`, restarting on failure. Deploy = push a new tag, then an SSM Run Command pulls it and restarts the unit.
- Alternative: copy the jar and run it directly (no image to maintain, but no portable artifact and no reuse of the existing `Dockerfile`).
- Alternative: public registry (zero cost, but images of a private prototype become public).

**3. Multi-stage `Dockerfile` on a maintained base.**
Build stage: a JDK 25 LTS image (Eclipse Temurin); runtime stage: a JRE-only Temurin 25 image, non-root user, the image entrypoint loading shared `runtime-jvm.args` (`-Xms64m -Xmx256m`), with no collector/metaspace overrides from the host unit. The build runs in CI/on the developer machine with `docker buildx --platform linux/arm64`, not on the instance.

The [Java 25 upgrade](../upgrade-backend-java-25/verification.md) aligns the repository toolchain and container runtime. Its local/container checks do not validate these planned host JVM flags. Recheck Linux host/native memory and swap on the selected instance; the earlier 192 MiB heap remains historical measurement evidence. Retain the prior Java 17 artifact/image as a complete rollback unit.

**4. Secrets in SSM Parameter Store SecureString.**
SecureString parameters under `/ecom/prod/`: `openai-api-key`, `qdrant-api-key`, `origin-verify-secret`. Supply the non-secret Qdrant hostname through deployment configuration; it may appear in existing connection diagnostics. The instance role may call `ssm:GetParameters` on that path only. A small start script (`ExecStartPre`) combines the fetched secrets with deployment configuration in the container environment (`OPENAI_API_KEY`, `QDRANT_HOST`, `QDRANT_API_KEY`, `QDRANT_USE_TLS=true`, `ECOM_ORIGIN_VERIFY_SECRET`). Standard-tier parameters are assumed free; verify.
- Alternative: Secrets Manager (a per-secret monthly fee, which is not justified for these credentials).

**5. `aws` Spring profile and log hygiene.**
The `aws` name denotes an ordinary Spring Boot deployment configuration, not an AWS login/IAM profile, log collector or CloudWatch integration. Activate `application-aws.yml` with `SPRING_PROFILES_ACTIVE=aws` in the host service. Keep the existing local defaults; no separate logging service is introduced.
`application-aws.yml` sets root and `com.ecomassistant` to INFO and retains `org.springframework.ai` at INFO. Preserve the existing ERROR overrides for `SpringAiRetryAutoConfiguration`, `DeepSeekChatModel` and `OpenAiChatModel`, which can otherwise log payloads. No advisor removal or JPA/SQL cleanup remains. Preserve the existing health-only Actuator exposure and verify the effective profile on normal and provider-error paths. ERROR overrides suppress known WARN payload leaks but are not by themselves proof that every ERROR path is safe; verify empty/malformed model responses and provider exceptions without logging request bodies, raw responses or unsanitised exception payloads. Docker uses the `local` or `journald` driver with a size cap (for example 10 MB x 3 files) so the 8 GB volume cannot fill up.

**6. `OriginVerifyFilter`.**
A servlet filter registered only when `ecom.origin-verify.secret` is non-empty. It compares the `X-Origin-Verify` header in constant time and answers 403 with an empty body otherwise. CloudFront adds the header as an origin custom header; control-plane readiness probes also pass through CloudFront, which adds the header. Lambda never receives the secret or connects directly to EC2. This closes the gap that the CloudFront prefix list admits traffic from any CloudFront distribution, not only ours.
- Local development: the property is unset, so the filter is not registered.

**7. Operations without SSH.**
SSM Session Manager (instance role `AmazonSSMManagedInstanceCore`) replaces SSH; port 22 is never opened. The instance has a public IPv4 (the Elastic IP) and reaches SSM, ECR, SSM Parameter Store, OpenAI and Qdrant Cloud over the internet, so no NAT gateway is needed.

**8. Readiness before traffic.**
The existing `GET /api/v1/health` endpoint reports readiness after catalog startup completes and while Qdrant is reachable. A running systemd unit alone does not prove readiness. Local verification must send `X-Origin-Verify` when origin verification is enabled; probes through CloudFront receive that header from the distribution. The control-plane Lambda and SPA use the endpoint for readiness; no new host-side health reporting mechanism is required.

## Risks / Trade-offs

- [1 GiB may be insufficient] → 256 MiB heap and 640 MiB container cap, default GC, no backend swap. Check total host memory/startup/latency; use small if needed. Host swap is emergency capacity, not normal workload capacity.
- [Public Elastic IP reachable by any CloudFront distribution] → Security group prefix list plus secret header filter (decision 6).
- [Plain HTTP between CloudFront and the origin] → Traffic carries product search text only, no accounts or payments; accepted for the prototype and fixed by the optional domain/HTTPS section in `aws-edge-hosting`.
- [Secrets visible in `docker inspect` or process environment on the host] → Access to the host is through SSM only, limited to the owner's IAM identity; no secrets in logs.
- [Elastic IP is billed while the instance is stopped] → Assumed about $3.65/month (public IPv4 pricing, verify); accepted as the price of a stable origin.
- [Free-tier or credits may not cover the selected instance] → Cost check task after the first week.

## Migration Plan

1. Verify the implemented MySQL removal, Qdrant Cloud configuration and readiness behavior; add only the AWS logging profile, origin-verification filter and non-root container user.
2. Deploy the stack, create the SSM parameters, push the first image, start the instance manually and verify `/api/v1/health` from the instance (Session Manager) and through CloudFront.
3. Hand start/stop over to `aws-control-plane`.
4. Rollback: stop the instance and redeploy the previous image tag; the host holds no state.

## Open Questions

- IaC tool: AWS CDK (TypeScript) is the default in this plan; Terraform is the alternative. Not decided by the owner.
- Deployment region and monthly budget. A future CloudFront viewer certificate must be in us-east-1 regardless of the EC2 region.
- Whether micro is acceptable after total-host validation, or small is needed.
- Custom domain: decided later; this change must work without it.
