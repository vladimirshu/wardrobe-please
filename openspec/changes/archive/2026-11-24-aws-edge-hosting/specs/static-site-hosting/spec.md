## ADDED Requirements

### Requirement: Single distribution with path-based routing
The system SHALL serve the storefront and the backend API from one CloudFront domain, routing `/api/v1/control/*` to the control Lambda, `/api/v1/*` to the backend private interface through the exported EC2 VPC Origin, and all other paths to a private S3 bucket holding the SPA build, without URL rewriting.

#### Scenario: Page request
- **WHEN** a browser requests `/`
- **THEN** `index.html` is served from the S3 bucket

#### Scenario: Search request
- **WHEN** the SPA sends `POST /api/v1/search` with an application/json body
- **THEN** the request is forwarded to the backend origin unchanged

#### Scenario: Wake request
- **WHEN** the SPA requests `/api/v1/control/up`
- **THEN** the request is forwarded to the control Lambda

#### Scenario: Direct bucket access
- **WHEN** a client requests an object from the S3 bucket URL directly
- **THEN** access is denied

### Requirement: Cache behavior
Content-hashed static assets SHALL be served with long immutable caching, `index.html` SHALL be cached for at most a short period, and responses under `/api/v1/` SHALL NOT be cached. The S3 behavior SHALL use managed CachingOptimized without an origin request policy and SHALL NOT forward the viewer Host to S3. Its one-second minimum TTL SHALL remain below the configured 60-second/one-year object lifetimes.

#### Scenario: New release
- **WHEN** a new SPA build is deployed and the entry paths are invalidated
- **THEN** visitors receive the new `index.html` referencing new hashed assets

#### Scenario: API response
- **WHEN** the same `/api/v1/health` request is made twice and the origin state changes between them
- **THEN** the second response reflects the new state


#### Scenario: Static bucket routing
- **WHEN** CloudFront fetches `index.html` from S3 for a viewer using the distribution domain
- **THEN** it uses the S3 origin Host, rather than forwarding the viewer Host

### Requirement: Fast, uncached failure of a stopped origin
Requests to the backend origin SHALL use a single connection attempt with a timeout of at most 3 seconds, and 5xx responses under `/api/v1/` SHALL NOT be cached, so a stopped instance yields a quick error that disappears as soon as the instance is ready.

#### Scenario: Instance stopped
- **WHEN** the SPA requests `/api/v1/health` while the instance is stopped
- **THEN** a 5xx response is returned within about 3 seconds

#### Scenario: Instance just became ready
- **WHEN** the instance becomes ready after a stopped period
- **THEN** the next `/api/v1/health` request returns 200 and not a cached error

### Requirement: Works without a custom domain
All behavior SHALL work on the distribution's default domain name; a custom domain SHALL be an optional later addition that requires no change to the SPA or the API paths.

#### Scenario: Default domain
- **WHEN** the SPA is opened on the `*.cloudfront.net` name
- **THEN** page, search and wake flows work over HTTPS

### Requirement: SPA deployment
The system SHALL provide a documented, repeatable deployment that uploads the SPA build with the correct cache headers and invalidates `/index.html` and `/`.

#### Scenario: Deploy
- **WHEN** the operator runs the deploy step with a fresh build
- **THEN** new files are in the bucket with their cache headers and the entry document is invalidated

### Requirement: JSON POST forwarding
The backend behavior SHALL allow POST, forward search JSON bodies and Content-Type unchanged, and disable search caching. This SHALL NOT introduce request-body logging or analytics tracking.

#### Scenario: Search body through the edge
- **WHEN** the SPA posts a JSON search through CloudFront
- **THEN** the backend receives the original body and Content-Type and the response is not cached

### Requirement: Repeatable edge integration
The full deployment SHALL deploy host, control and edge in dependency order, bind the control health URL and distribution-scoped invocation permissions after edge creation, then publish the SPA. It SHALL retain the configured schedule state and default host shutdown behavior on success or ordinary deployment failure.

#### Scenario: Fresh edge deployment
- **WHEN** the full deployment completes
- **THEN** the control Lambda uses the default CloudFront HTTPS health URL, its grants target that distribution, and SPA publication occurs after binding
