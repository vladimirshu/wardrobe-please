## Archive handoff — 2026-11-24

Archived and synced at the owner's explicit request after implementation.
Vladimir will perform the outstanding live verification himself. Unchecked tasks
remain unchecked; archiving does not establish deployment success, acceptance or
observed costs. Optional items remain deferred. See the task and verification
records for the remaining checks. No deployment or AWS verification was run as
part of archiving.

## 1. Backend preparation

- [x] 1.1 Add `application-aws.yml` with root/application INFO logging; preserve the existing payload-sensitive Spring AI ERROR overrides and health-only Actuator exposure. Activate it through host `SPRING_PROFILES_ACTIVE=aws` (ordinary Spring configuration, no AWS login/logging service). Verify no prompts or provider payloads are logged on successful searches, empty/malformed model responses or provider-error paths
- [x] 1.2 Remove the application origin filter/property and origin-secret handling; enforce the boundary through VPC Origins and security groups instead
- [x] 1.3 Tests: ordinary requests require no origin header; log checks that the `aws` profile emits no message text on successful searches or provider-error paths
- [x] 1.4 Configure a non-root user in `wardrobe-be/Dockerfile`, build with `docker buildx --platform linux/arm64`, and verify that the image starts with the existing Qdrant connection configuration; retain the current build/runtime stages

## 2. Infrastructure definition

- [x] 2.1 Create the IaC project under `infra/` (tool per design Open Questions) with a stack for the host: VPC use (default VPC is acceptable), security group (8080 from the AWS-managed VPC-origin service security group only; no public/prefix-list ingress), Elastic IP, `t4g.micro` evaluation instance (upgrade to small on failed host validation), 8 GB gp3 volume, IMDSv2 required
- [x] 2.2 Instance role: `AmazonSSMManagedInstanceCore`, ECR pull, `ssm:GetParameters` on the two exact provider-key parameter ARNs only; create the SecureString parameters (values supplied out of band, never committed)
- [x] 2.3 ECR repository with lifecycle policy (keep last 3 images)
- [x] 2.4 Instance bootstrap (user data): install Docker, create 1 GB swap file, configure the Docker log driver with a size cap, install the systemd unit and the `ExecStartPre` script that reads the parameters into the container environment, using the image's shared `-Xms64m -Xmx256m` preset and default GC, with `--memory=640m --memory-swap=640m` and no metaspace override
- [x] 2.5 Create the EC2 VPC Origin, discover its service-managed security group after creation, and export the VPC Origin ID/private DNS for the edge stack and instance ID for control; retain EIP only for outbound internet

## 3. Deploy and verify

- [ ] 3.1 Migrate network rules before activating the filter-free image; update the existing host prepare script via SSM; verify local/CloudFront health without special headers, successful outbound API calls, blocked direct/foreign CloudFront connections, and retained private-origin connectivity after stop/start
- [ ] 3.2 Load check: ten minutes of searches, record resident memory and swap use; include startup/reindex and OS/Docker/SSM headroom, decide micro vs small and record it in the runbook
- [ ] 3.3 Verify no secret and no prompt text in `docker logs` and in the container image layers

## 4. Runbook and cost

- [x] 4.1 Write `infra/RUNBOOK.md`: first-time setup (parameters, first image push), deploy (push tag, SSM Run Command), rollback (previous tag), reading logs, rotating a secret, replacing the instance
- [x] 4.2 Before the first deploy, record a regional monthly estimate including compute runtime, EBS, public IPv4, ECR and transfer/operational charges; configure budget notifications for the agreed monthly budget
- [ ] 4.3 Cost verification: after the first full week, check Cost Explorer for instance, EBS, Elastic IP and data transfer lines against the assumptions in this design and record actual figures in the runbook

## Current status — VPC-origin revision (2026-11-23)

Implementation checkboxes describe repository code, not deployed acceptance.
The old public-origin deployment/readiness results are retained as historical
verification. This revision is local only: no stack update, image publication,
host startup or SSM parameter deletion has run. Task 3.1 is reopened because the
network trust boundary changed. Edge distribution and control-plane deployment
remain separate; cold indexing/load, final live logs and first-week costs remain open.
