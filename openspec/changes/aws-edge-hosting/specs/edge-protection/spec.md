## ADDED Requirements

### Requirement: Origin custom header
CloudFront SHALL add the secret header `X-Origin-Verify` to every request sent to the backend origin, using the same secret the backend verifies.

#### Scenario: Request via CloudFront
- **WHEN** a search request passes through the distribution
- **THEN** the backend receives the `X-Origin-Verify` header with the configured secret

### Requirement: Lambda reachable only via CloudFront
The control Lambda Function URL SHALL be invocable only through the distribution's origin access control.

#### Scenario: Direct invocation
- **WHEN** the Function URL is called without CloudFront's signature
- **THEN** the call is rejected

### Requirement: Default plan WAF protection
The distribution SHALL retain the selected plan's default WAF protection and any required associated web ACL. The migration SHALL NOT introduce custom WAF rules, search-path rate rules, exclusions, browser challenges or custom block responses. Existing backend search limits SHALL remain unchanged. Edge blocks SHALL retain AWS's default response and SHALL NOT be required to return HTTP 429.

#### Scenario: Deploying edge protection
- **WHEN** the distribution is provisioned
- **THEN** it uses the plan's default WAF protection without project-specific rules or custom responses

#### Scenario: Compatibility with application traffic
- **WHEN** page loads, POST search and automated readiness probes are verified against the deployed distribution
- **THEN** they work with default protection, or the incompatibility is recorded for a separate decision without silently adding custom WAF configuration or a paid upgrade

### Requirement: Automated readiness probes
Any enabled WAF rules SHALL permit control-Lambda requests to `/api/v1/health` without a browser challenge. CloudFront SHALL route those requests to EC2 and add the origin-verification header, without granting Lambda direct network access to EC2 or sharing the secret with it.

#### Scenario: Control Lambda health check
- **WHEN** the control Lambda requests the CloudFront HTTPS health URL
- **THEN** WAF permits the automated probe and the request reaches the backend through the existing CloudFront-only network restriction

### Requirement: Security headers
The default behavior SHALL use AWS-managed `SecurityHeadersPolicy` (ID `67f7725c-6f97-4210-82d7-5512b31e9d03`) without custom policies or overrides. Its managed header set SHALL be sufficient for this migration; a custom CSP, `frame-ancestors` directive or edge-function workaround SHALL NOT be required.

#### Scenario: Page response
- **WHEN** `index.html` is served
- **THEN** the response carries the AWS-managed security headers, including `X-Frame-Options: SAMEORIGIN`; no CSP header is required

### Requirement: Secret handling
The origin-verification secret SHALL be generated once, stored only in SSM Parameter Store and in the deployed distribution configuration, and SHALL NOT appear in the repository.

#### Scenario: Repository scan
- **WHEN** the repository is searched for the secret value
- **THEN** it is not found
