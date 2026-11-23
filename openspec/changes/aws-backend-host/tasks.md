## 1. Backend preparation

- [ ] 1.1 Add `application-aws.yml` with root/application INFO logging; preserve the existing payload-sensitive Spring AI ERROR overrides and health-only Actuator exposure. Activate it through host `SPRING_PROFILES_ACTIVE=aws` (ordinary Spring configuration, no AWS login/logging service). Verify no prompts or provider payloads are logged on successful searches, empty/malformed model responses or provider-error paths
- [ ] 1.2 Add `ecom.origin-verify.secret` property (env `ECOM_ORIGIN_VERIFY_SECRET`) and a conditional `OriginVerifyFilter` (constant-time compare, 403 empty body)
- [ ] 1.3 Tests: filter allows the correct header, rejects a missing/wrong header, is not registered without the secret; log checks that the `aws` profile emits no message text on successful searches or provider-error paths
- [ ] 1.4 Configure a non-root user in `wardrobe-be/Dockerfile`, build with `docker buildx --platform linux/arm64`, and verify that the image starts with the existing Qdrant connection configuration; retain the current build/runtime stages

## 2. Infrastructure definition

- [ ] 2.1 Create the IaC project under `infra/` (tool per design Open Questions) with a stack for the host: VPC use (default VPC is acceptable), security group (8080 from the CloudFront origin-facing prefix list only), Elastic IP, `t4g.micro` evaluation instance (upgrade to small on failed host validation), 8 GB gp3 volume, IMDSv2 required
- [ ] 2.2 Instance role: `AmazonSSMManagedInstanceCore`, ECR pull, `ssm:GetParameters` on `/ecom/prod/*` only; create the SecureString parameters (values supplied out of band, never committed)
- [ ] 2.3 ECR repository with lifecycle policy (keep last 3 images)
- [ ] 2.4 Instance bootstrap (user data): install Docker, create 1 GB swap file, configure the Docker log driver with a size cap, install the systemd unit and the `ExecStartPre` script that reads the parameters into the container environment, using the image's shared `-Xms64m -Xmx256m` preset and default GC, with `--memory=640m --memory-swap=640m` and no metaspace override
- [ ] 2.5 Export the Elastic IP DNS name and origin-verify secret parameter name for `aws-edge-hosting`, and the instance id for `aws-control-plane`; control needs neither the origin address nor the secret

## 3. Deploy and verify

- [ ] 3.1 Push the first image, start the instance manually, check from Session Manager that `GET /api/v1/health` with the configured `X-Origin-Verify` header returns 200 after catalog readiness, and that a direct connection to the Elastic IP from the internet is refused
- [ ] 3.2 Load check: ten minutes of searches, record resident memory and swap use; include startup/reindex and OS/Docker/SSM headroom, decide micro vs small and record it in the runbook
- [ ] 3.3 Verify no secret and no prompt text in `docker logs` and in the container image layers

## 4. Runbook and cost

- [ ] 4.1 Write `infra/RUNBOOK.md`: first-time setup (parameters, first image push), deploy (push tag, SSM Run Command), rollback (previous tag), reading logs, rotating a secret, replacing the instance
- [ ] 4.2 Before the first deploy, record a regional monthly estimate including compute runtime, EBS, public IPv4, ECR and transfer/operational charges; configure budget notifications for the agreed monthly budget
- [ ] 4.3 Cost verification: after the first full week, check Cost Explorer for instance, EBS, Elastic IP and data transfer lines against the assumptions in this design and record actual figures in the runbook
