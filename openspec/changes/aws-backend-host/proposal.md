## Why

The backend only runs on a developer machine (`docker-compose.yml` already starts the backend, frontend and Qdrant; MySQL has been removed). To show the prototype to hiring managers it must run on AWS at near-zero cost, reachable only through CloudFront, with secrets outside the image and logs that do not fill a small disk. The chosen host is a single small EC2 instance with a stable Elastic IP, because an ALB (about $16/month, assumption to verify) or a per-start IP change on Fargate would cost more or need extra moving parts. The instance is stopped most of the time (see `aws-control-plane`), so everything it needs must survive stop/start.

## What Changes

- Provision one EC2 `t4g.micro` (Graviton, 1 GiB RAM, evaluation candidate; small fallback, verify free-tier/credit eligibility) with an 8 GB gp3 volume, a 1 GB swap file, IMDSv2 only, and an Elastic IP.
- Security group: inbound TCP 8080 only from the CloudFront origin-facing managed prefix list; no SSH. Administration through SSM Session Manager.
- Run the backend as a container pulled from a private ECR repository (arm64 image), started by systemd at boot, using the shared `-Xms64m -Xmx256m` preset and JVM-default GC, with a 640 MiB container limit and no container swap.
- Secrets (OpenAI API key, Qdrant Cloud API key, origin-verification secret) live in SSM Parameter Store SecureString parameters and are read at container start; the non-secret Qdrant hostname is deployment configuration; no secret is baked into the image or the user data.
- Add an `aws` Spring profile with INFO logging and verify that logs omit prompt text. Configure Docker log rotation.
- Add a backend `OriginVerifyFilter`, enabled only when `ECOM_ORIGIN_VERIFY_SECRET` is set, rejecting requests without the matching `X-Origin-Verify` header.
- Build the existing multi-stage Temurin image for arm64 and run it as a non-root user.
- Document a deployment runbook and a cost check.

## Capabilities

### New Capabilities
- `backend-host`: the EC2 host, its network exposure, secrets handling, process supervision, logging policy and origin verification.

### Modified Capabilities
<!-- none: no existing infra spec exists; backend request behavior is unchanged -->

## Impact

- New infrastructure code (CDK app under `infra/`, see design Open Questions), a non-root runtime user in the existing multi-stage `Dockerfile`, `application-aws.yml`, `OriginVerifyFilter` in `wardrobe-be`.
- Existing prerequisites are implemented: MySQL removal, Qdrant Cloud connection configuration and `/api/v1/health` readiness. Verify these on AWS rather than reimplementing them. Coordinate with `aws-edge-hosting` (CloudFront prefix-list and secret header) and the currently proposed `aws-control-plane` (start/stop of this instance).
- Cost: estimate instance runtime, EBS, public IPv4, ECR storage and any transfer/operational charges before deployment; do not assume free-tier eligibility or a stopped-instance estimate covers always-on operation.
