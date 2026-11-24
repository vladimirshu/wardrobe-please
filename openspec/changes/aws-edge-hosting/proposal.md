## Why

The storefront is a client-only Vite SPA and the backend and control Lambda sit behind different origins. One CloudFront distribution gives a single domain (no CORS), HTTPS from the browser, caching of static files, protection of the origin, and built-in traffic metrics and reports without a separate analytics system. The owner selected pay-as-you-go CloudFront without WAF on 2026-11-24: its recurring usage allowance suits this demo and supports VPC Origins. The Free flat-rate plan was rejected because it excludes VPC Origins.

## What Changes

- Create a private S3 bucket for the SPA build, served through CloudFront with origin access control; hashed assets cached as immutable, `index.html` with a short TTL.
- Create one CloudFront distribution with three origins and path behaviors: `/*` → S3, `/api/v1/control/*` → Lambda Function URL (origin access control), `/api/v1/*` → EC2 private interface over HTTP 8080 through the exported CloudFront VPC Origin, without a secret header.
- For `/api/v1/*`: allow POST search and forward its JSON body and Content-Type unchanged; verify POST search works through the private origin; keep caching disabled, origin connection attempts 1, connection timeout 2-3 seconds, error caching minimum TTL 0, so a stopped instance yields a fast, uncached 5xx.
- Protect origins using the managed VPC-origin service security group (defined in `aws-backend-host`) and S3/Lambda OAC. Use included Shield Standard; create no WAF web ACL, WAF rules or paid Shield subscription. Existing backend search limits remain unchanged; no edge application-request filtering or wake spending cap is introduced.
- Rely on default CloudFront metrics and available console reports at no additional analytics cost; no access-log delivery, analytics storage, custom queries or frontend tracking.
- Add a deploy step for the SPA (sync to S3, invalidate `/index.html` and `/`) and an optional section for a custom domain (ACM certificate in us-east-1, DNS alias), explicitly not required.

## Capabilities

### New Capabilities
- `static-site-hosting`: S3 + CloudFront delivery of the SPA, routing of API paths, cache and timeout behavior.
- `edge-protection`: private-origin/OAC restrictions and AWS-managed security headers, without WAF.
- `visit-statistics`: basic traffic visibility through built-in CloudFront metrics and console reports; no visitor, campaign or wake-event tracking.

### Modified Capabilities
<!-- none -->

## Impact

- New infrastructure code (pay-as-you-go distribution and SPA bucket, without WAF) and a frontend deploy script.
- Depends on: `aws-backend-host` (VPC Origin ID, private DNS and managed-SG-only ingress), `aws-control-plane` (Function URL), `static-client-frontend` work in `storefront-search-ui`/`service-availability-gate` (build output with hashed assets), `api-versioned-search-path` (`/api/v1` prefix makes path routing possible without URL rewriting).
- Cost assumptions: recurring CloudFront allowance (10 million requests and 1 TB/month, shared account/organization limits), separately billed S3/control operations and POST transfer to origins. Estimated scheduled-host total is about €9/month with illustrative VAT; no flat-rate overage protection or spending cap. Verify allowance availability and actual costs after deployment. See `verification.md`.
