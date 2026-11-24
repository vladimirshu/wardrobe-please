# Current revision — CloudFront VPC Origin (2026-11-23)

This revision is local repository work only. The deployed stack/old ECR images
below have not been migrated and must not be treated as VPC-origin acceptance.
Source now removes the application origin filter/property and SSM origin secret,
creates an EC2 VPC Origin and discovers its AWS-managed source security group,
permits only that group on 8080, and retains the public IP/IGW route for egress.
Use the runbook migration order: restrict the network before disabling the old
filter and update existing host scripts through SSM, not only EC2 user data.
No stack deployment, AWS reads, host startup, image publication or parameter deletion
is performed in this revision. Cold indexing/load, stop/start, outgoing provider
access, foreign-distribution rejection and final live logs remain unverified for it.

Local checks for this revision:
- Full Gradle backend test suite passed: XML reports contain 514 tests, zero
  failures/errors and four skipped optional DeepSeek cases. Requests in the AWS
  logging tests succeed without any origin header; obsolete filter tests are removed.
- IntelliJ project build passed with no reported problems.
- TypeScript compilation and four infrastructure assertions passed: private EC2
  VPC Origin, only managed-source-group 8080 ingress, no shared prefix-list/public
  rule or origin-secret dependency, VPC-scoped lookup after origin creation, two-key
  IAM access, and retained EIP/memory/log constraints.
- Strict CDK synthesis passed with cached VPC context and `--lookups=false`;
  `AWS_REGION=eu-central-1` selects the target region without fresh AWS lookups.
- Python startup helper compiled. Strict OpenSpec validation passed for
  backend-host, edge-hosting and control-plane; scoped whitespace checks passed.
- No new Docker image was built/published; old image acceptance is not acceptance
  of this revision. Live checks remain pending as listed above.

---

## Deployment wrapper — 2026-11-24

- Added the commented `infra/deploy.sh` entry point, example/local JSON configuration
  and AWS CLI/SSM helper; the personal configuration is ignored by Git.
- TypeScript compilation and the existing four infrastructure tests passed.
- Eight offline Python tests passed for existing/fresh stack paths, migration order,
  removal of broad ingress only from a stack-owned SG, final ingress rejection,
  SSM payload generation, failure cleanup and explicit keep-running configuration.
- Bash syntax and Python source compilation passed. The embedded remote install
  and readiness programs compile in the mocked SSM test.
- No AWS calls, Docker builds/publication, host startup or deployment were performed
  for the wrapper change. Live end-to-end migration remains pending.

---

## Historical public-origin/header deployment (superseded design)

### Earlier checks — 2026-11-23

## Implementation and local checks

- CDK TypeScript approved by the owner; deployed using `wardrobe` to account
  `708564192046`, eu-central-1. Monthly target €10; email recipient supplied by
  the owner. `wardrobe-monthly` is healthy, $8 USD, actual 80%/100% notifications.
  AWS reports budgets in USD; alerts do not enforce a spending cap.
- `QDRANT_USE_TLS=false JAVA_HOME=$(…java_home -v 25) ./gradlew test --no-daemon`:
  **293 tests, 0 failures/errors, 4 skipped** (optional runtime tests).
  The cloud TLS setting must not be inherited by local Testcontainers. An initial
  run without that override failed Qdrant connections; the corrected run passed.
- AWS profile privacy tests exercise real HTTP provider adapters against WireMock
  and local Qdrant/fake embeddings, for query analysis and refinement: success,
  empty/schema-invalid/malformed content, authentication errors, transient failures,
  and arbitrary unknown SKU output. No private prompt/payload markers or test
  Authorization value appeared in captured logs.
- These tests found unsanitized refinement provider exceptions and a log that
  echoed unknown SKUs. Both were fixed; provider failure causes are discarded at
  the advisor boundary and operational diagnostics retain only safe class names.
- Origin filter: correct/missing/wrong/non-ASCII headers and absent/empty configured
  secret tested. Registration precedes deadline/admission/security and covers all
  dispatcher types. Comparison uses fixed-size SHA-256 digests and
  `MessageDigest.isEqual`. Rejection is an empty 403.
- IntelliJ project build passed. CDK TypeScript compilation, three infrastructure
  assertions, strict synth, reviewed diff and OpenSpec strict validation passed.
- Final Temurin 25 image built using `docker buildx --platform linux/arm64`;
  runtime user `10001:10001`. Full image archive/layers scanned for configured
  OpenAI/Qdrant keys: absent. No credential is passed to Docker build.
- Local non-root container smoke with remote Qdrant: readiness 200; missing header
  403/zero bytes; injected secrets absent from logs; 408.1 MiB / 640 MiB observed
  before the final provider-exception-only fix. AWS checks use the final image.

## Deployed resources

- Stack: `WardrobeBackendHost` (`CREATE_COMPLETE`).
- Instance: `i-0cd429d1037fba686`, `t4g.micro`, ARM64, encrypted 8 GB gp3, IMDSv2
  required, standard CPU credits, no SSH; managed through SSM (Online).
- Elastic IP: `63.189.27.227`.
- Origin DNS: `ec2-63-189-27-227.eu-central-1.compute.amazonaws.com`.
- ECR: `708564192046.dkr.ecr.eu-central-1.amazonaws.com/wardrobebackendhost-backendrepository9b711290-kzh0nkw6t8vx:initial`.
- Local image ID: `sha256:297cf015f4f81d881c020ec7049a8e40868be935e89b2fae982986c7ed3d2086`.
- Three Standard SecureStrings were created from existing environment credentials
  and a generated origin secret. Values were not printed or committed. Instance
  reads are restricted to exact ARNs, with an explicit deny overriding wildcard
  reads granted by the SSM managed baseline.
- External TCP connection to `63.189.27.227:8080` timed out as expected. The sole
  inbound rule is 8080 from the CloudFront managed prefix list `pl-a3a144ca`.

## Live acceptance

Initial deployed image: authenticated health returned 200 / `{"status":"UP"}`;
missing header returned 403 / zero bytes. Docker reported 288.2 MiB / 640 MiB,
339272 kB host memory available, 1036284 kB swap free, no container OOM. Catalog
vectors were reused. Injected credentials were absent from container logs.

The owner requested wrapping up before extended acceptance. The instance is stopped.
Cold reindex, ten-minute searches, crash recovery and stop/start acceptance did not run;
**micro is not accepted for capacity/latency yet**. Bootstrap and first application
startup were slow with standard CPU credits; do not infer warm-wake latency from this.

Final source includes an additional generic provider exception handler for embedding/
retrieval, covered by the final regression test. It is built and published as ECR tag
`privacy-final`, but **not activated**: the stopped host config and stack ImageTag
remain `initial`. Activate the final tag using the runbook before further live testing.
Final-image live log acceptance remains pending; the recorded AWS observations above
are for `initial`.
CloudFront end-to-end readiness/search remain pending `aws-edge-hosting`.

## Cost

Regional rates and deterministic calculations are in [`infra/RUNBOOK.md`](../../../infra/RUNBOOK.md).
Estimated backend baseline before tax: $7.6684 at 308 runtime hours/month,
$11.7196 always-on, $4.7116 stopped. Add transfer/operations, tax and currency effects;
OpenAI/Qdrant and future edge resources are separate. Do not leave the instance
running continuously while the separate control-plane schedule is unimplemented.
The first full week's actual Cost Explorer reconciliation is future work.
