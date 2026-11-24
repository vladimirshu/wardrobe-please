# backend-host Specification

## Purpose
Define the AWS backend host, private CloudFront-origin access, container operation, secrets and cost controls.

## Verification status
Synced from the implemented change on 2026-11-24 at the owner's request. Live
acceptance and outstanding operational/cost checks remain unverified and will be
performed by Vladimir. Archiving is not verification. See [remaining tasks](../../changes/archive/2026-11-24-aws-backend-host/tasks.md)
and [verification record](../../changes/archive/2026-11-24-aws-backend-host/verification.md).

## Requirements
### Requirement: Origin reachable only through CloudFront VPC Origins
The backend host SHALL accept inbound TCP 8080 only from the AWS-managed
`CloudFront-VPCOrigins-Service-SG` in its VPC. No inbound rule SHALL admit public
CIDRs or the shared CloudFront origin-facing prefix list. Port 22 and all other
inbound ports SHALL remain closed. CloudFront SHALL reach the instance's private
address through a VPC Origin. The VPC Origin SHALL NOT be shared with other accounts.

#### Scenario: Direct request from the internet
- **WHEN** any internet client, including another account's public CloudFront origin, connects to the Elastic IP on port 8080
- **THEN** the connection is not accepted by the security group

#### Scenario: Request through the project's VPC Origin
- **WHEN** the project's distribution sends a request over its VPC Origin connection
- **THEN** the backend security group admits TCP 8080 from the service-managed security group without a secret HTTP header

#### Scenario: Creating the managed security group
- **WHEN** the VPC Origin is provisioned
- **THEN** AWS creates its service-managed security group, infrastructure discovers it within the instance VPC after origin creation, and only then adds the source-security-group ingress rule without creating or editing AWS's group

### Requirement: Outbound internet access remains independent
The instance SHALL retain an Elastic IP and a public-subnet IPv4 default route to
the VPC's Internet Gateway for outbound access to OpenAI, Qdrant, ECR and SSM.
NAT Gateway SHALL NOT be required. Replies to allowed outbound connections SHALL
remain permitted by stateful security-group handling. The public address SHALL NOT
be used as CloudFront's backend origin address.

#### Scenario: Outbound API request
- **WHEN** the backend initiates an allowed internet API request
- **THEN** the request and its reply succeed while unsolicited public inbound connections remain blocked

#### Scenario: Address after restart
- **WHEN** the instance is stopped and started again
- **THEN** it retains the Elastic IP and private primary interface and the deployed VPC Origin readiness path is verified again

### Requirement: No application origin secret
The backend SHALL NOT implement an origin-verification filter, require an
origin-verification property or header, fetch an origin-verification SSM parameter,
or export an origin-secret parameter name. Infrastructure SHALL provide the origin
ID and private DNS name needed by the edge stack instead.

#### Scenario: Host-local readiness
- **WHEN** the operator requests `/api/v1/health` from the instance without custom headers
- **THEN** ordinary readiness behavior applies, including 200 after catalog readiness

#### Scenario: Local development
- **WHEN** the backend runs locally
- **THEN** it requires no CloudFront header or origin secret

### Requirement: Secrets outside the image and the repository
The system SHALL store the OpenAI API key and the Qdrant Cloud API key as SSM Parameter Store SecureString parameters, SHALL grant the instance role read access to only those two exact parameter ARNs, and SHALL NOT include any secret value in the container image, instance user data, repository files or application logs. The Qdrant hostname SHALL be non-secret deployment configuration; connection diagnostics MAY include it.

#### Scenario: Start with secrets
- **WHEN** the backend container starts on the instance
- **THEN** it receives the secret values as environment variables read from Parameter Store at start

#### Scenario: Log inspection
- **WHEN** the application logs of a normal search session are searched for the key values
- **THEN** no secret value appears

### Requirement: Unattended start and supervision
The host SHALL start the backend container automatically on every boot and SHALL restart it when it exits abnormally, without manual action.

#### Scenario: Start after the instance is started
- **WHEN** the instance transitions from stopped to running
- **THEN** the container is started and `GET /api/v1/health` returns 200 after startup completes

#### Scenario: Crash
- **WHEN** the backend process exits with an error
- **THEN** it is restarted automatically

### Requirement: Production logging policy
The host SHALL activate the ordinary Spring Boot `aws` deployment profile through `SPRING_PROFILES_ACTIVE=aws`; this SHALL NOT require an AWS login profile or remote log service. With this profile active, the backend SHALL use INFO as its default logging level while preserving existing payload-sensitive Spring AI ERROR overrides, SHALL NOT log customer prompts, LLM requests/responses or SQL, and the container log driver SHALL cap total log size so logs cannot exhaust the volume.

#### Scenario: Search under the aws profile
- **WHEN** a search is processed with the `aws` profile
- **THEN** the application log contains no message text or LLM payload

#### Scenario: Provider error paths
- **WHEN** the model returns an empty/malformed response or a provider call raises an exception under the aws profile
- **THEN** logs contain sanitised operational error information without user message text, raw provider payloads or credentials

#### Scenario: Long uptime
- **WHEN** the instance runs for weeks under traffic
- **THEN** container logs occupy no more than the configured cap

### Requirement: Memory-bounded runtime
The runtime SHALL use shared 64–256 MiB JVM heap limits and JVM-default collector
selection, with no explicit metaspace cap. The backend container SHALL have a
640 MiB memory limit and no swap access. A 1 GiB micro host SHALL be evaluated
first, with small as fallback if total-host headroom/startup/reindex or load checks
fail. The instance SHALL retain emergency host swap, which SHALL NOT count as
normal workload capacity. Normal search SHALL NOT cause an OS/container OOM kill.

#### Scenario: Search load
- **WHEN** a sequence of searches runs for ten minutes
- **THEN** the process stays running and container memory stays within the selected profile's cap, OS/Docker/SSM have adequate measured headroom, and the host has no sustained swap pressure

### Requirement: Administration without SSH
The instance SHALL be administrable through SSM Session Manager and SSM Run Command, and IMDSv2 SHALL be required.

#### Scenario: Deploying a new image
- **WHEN** the operator runs the documented deploy command after pushing an image tag
- **THEN** the instance pulls that tag and restarts the service without any inbound connection to the instance

#### Scenario: One-command deployment
- **WHEN** the operator runs `bash infra/deploy.sh` with the local budget email configured and prerequisites available
- **THEN** it checks the target account, builds a unique ARM64 image, deploys all registered CDK stacks through CloudFormation, publishes the image and updates the host via SSM
- **AND** it reuses the existing stack's Qdrant hostname unless overridden, requiring an explicit hostname for a fresh stack
- **AND** it closes historical broad stack-owned ingress before host startup and verifies managed-group-only ingress before activating the new image
- **AND** it checks host-local readiness and stops EC2 after completion or ordinary deployment failure by default, unless keeping it running was explicitly configured
- **AND** it neither reads provider secret values locally nor claims deployment of unimplemented edge/control-plane resources

