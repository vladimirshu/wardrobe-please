## ADDED Requirements

### Requirement: Private backend origin
CloudFront SHALL forward backend requests over the existing EC2 VPC Origin to the
instance's private interface. The backend security group SHALL admit TCP 8080 only
from AWS's VPC-origin service security group, not public CIDRs or the shared
CloudFront prefix list. The origin SHALL NOT be shared across accounts. No custom
origin-authentication header or application origin filter SHALL be required.

#### Scenario: Request via CloudFront
- **WHEN** a search request passes through the distribution
- **THEN** its JSON body reaches EC2 over the VPC-origin private path without an origin secret

#### Scenario: Another account's distribution
- **WHEN** an unrelated distribution targets the public EC2 address
- **THEN** the security group rejects the connection

#### Scenario: Outbound internet remains available
- **WHEN** EC2 calls OpenAI or Qdrant through its retained public IP and Internet Gateway route
- **THEN** outgoing calls and their replies work without opening public inbound access

### Requirement: Lambda reachable only via CloudFront
The control Lambda Function URL SHALL be invocable only through the distribution's origin access control.

#### Scenario: Direct invocation
- **WHEN** the Function URL is called without CloudFront's signature
- **THEN** the call is rejected

### Requirement: Pay-as-you-go without WAF
The distribution SHALL use pay-as-you-go pricing without a flat-rate plan or associated WAF web ACL. The migration SHALL NOT provision WAF rules, challenges, custom block responses or paid Shield Advanced. Included Shield Standard and existing backend search rate limits SHALL remain. Documentation SHALL identify the absence of application-request filtering and a wake spending cap.

#### Scenario: Deploying edge protection
- **WHEN** the distribution is provisioned
- **THEN** private-origin/OAC restrictions apply without a WAF web ACL or pricing-plan subscription

#### Scenario: Backend rate limit
- **WHEN** the backend search rate limit is exceeded
- **THEN** its existing HTTP 429 response passes through unchanged, without introducing an edge rate rule

### Requirement: Automated readiness probes
CloudFront SHALL route control-Lambda requests to `/api/v1/health` through the VPC Origin without a browser challenge, granting Lambda direct EC2 network access or introducing an origin secret.

#### Scenario: Control Lambda health check
- **WHEN** the control Lambda requests the CloudFront HTTPS health URL
- **THEN** the request reaches the backend through the existing CloudFront-only network restriction without WAF inspection

### Requirement: Security headers
The default behavior SHALL use AWS-managed `SecurityHeadersPolicy` (ID `67f7725c-6f97-4210-82d7-5512b31e9d03`) without custom policies or overrides. Its managed header set SHALL be sufficient for this migration; a custom CSP, `frame-ancestors` directive or edge-function workaround SHALL NOT be required.

#### Scenario: Page response
- **WHEN** `index.html` is served
- **THEN** the response carries the AWS-managed security headers, including `X-Frame-Options: SAMEORIGIN`; no CSP header is required

### Requirement: No origin-verification secret
The edge deployment SHALL consume the backend VPC Origin ID/private DNS and SHALL
NOT require an SSM origin-verification parameter, custom authentication header or
application origin-verification property.

#### Scenario: Deploying backend routing
- **WHEN** the backend behavior is configured
- **THEN** it binds to the exported VPC Origin, without reading or injecting an origin-verification secret
