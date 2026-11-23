## Why

The storefront is a client-only Vite SPA and the backend and control Lambda sit behind different origins. One CloudFront distribution gives a single domain (no CORS), HTTPS from the browser, caching of static files, protection of the origin, and built-in traffic metrics and reports without a separate analytics system. The owner assumes CloudFront's free flat-rate plan (1 million requests included) covers the traffic; this must be verified, including which features the plan allows.

## What Changes

- Create a private S3 bucket for the SPA build, served through CloudFront with origin access control; hashed assets cached as immutable, `index.html` with a short TTL.
- Create one CloudFront distribution with three origins and path behaviors: `/*` → S3, `/api/v1/control/*` → Lambda Function URL (origin access control), `/api/v1/*` → EC2 origin over HTTP port 8080 with the `X-Origin-Verify` custom header.
- For `/api/v1/*`: allow POST search and forward its JSON body and Content-Type unchanged; verify POST search works with the plan’s default WAF protection; keep caching disabled, origin connection attempts 1, connection timeout 2-3 seconds, error caching minimum TTL 0, so a stopped instance yields a fast, uncached 5xx.
- Protect the origin and the edge: security group limited to the CloudFront origin-facing prefix list (defined in `aws-backend-host`), secret header, and the selected plan’s default WAF protection. Do not create custom WAF rules, exclusions, challenges or block responses.
- Rely on default CloudFront metrics and available console reports at no additional analytics cost; no access-log delivery, analytics storage, custom queries or frontend tracking.
- Add a deploy step for the SPA (sync to S3, invalidate `index.html`) and an optional section for a custom domain (ACM certificate in us-east-1, DNS alias), explicitly not required.

## Capabilities

### New Capabilities
- `static-site-hosting`: S3 + CloudFront delivery of the SPA, routing of API paths, cache and timeout behavior.
- `edge-protection`: origin lock-down, default plan WAF protection and AWS-managed security headers.
- `visit-statistics`: basic traffic visibility through built-in CloudFront metrics and console reports; no visitor, campaign or wake-event tracking.

### Modified Capabilities
<!-- none -->

## Impact

- New infrastructure code (distribution, SPA bucket, the plan-associated default WAF protection) and a frontend deploy script.
- Depends on: `aws-backend-host` (origin address, secret, prefix-list rule), `aws-control-plane` (Function URL), `static-client-frontend` work in `storefront-search-ui`/`service-availability-gate` (build output with hashed assets), `api-versioned-search-path` (`/api/v1` prefix makes path routing possible without URL rewriting).
- Cost assumptions to verify: CloudFront free plan, SPA S3 storage in cents, plan eligibility and included default WAF protection; no separately billed custom WAF configuration or paid upgrade is required by this proposal. Existing backend search limits remain in effect.
