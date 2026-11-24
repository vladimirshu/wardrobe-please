## Context

### Approved pricing decision — 2026-11-24

The owner approved **pay-as-you-go CloudFront without WAF**, preserving private
origins, OAC and backend search limits. The previous Free flat-rate assumption
was rejected because VPC Origins require Business or higher among flat-rate plans.
See [verification](verification.md) for sources, estimates and the decision history.
The scheduled-host estimate is about €9/month including illustrative VAT, with
little margin. No edge application-request filtering or wake spending cap is added.

Traffic estimate from the owner: about 2 visitors per day with about 20 API calls each, plus page loads, so a few thousand requests per month, far below 1 million. The SPA is built by Vite into static files. The backend serves `/api/v1/search` and `/api/v1/health` from an EC2 origin that is stopped most of the time; the control Lambda serves `/api/v1/control/up`.

Repository implementation uses CDK TypeScript. Deployment, actual account allowance availability and live acceptance are pending. No flat-rate subscription, WAF association, paid Shield, access-log delivery or additional paid metrics are provisioned.

## Goals / Non-Goals

**Goals:**
- One public origin name for page, API and control calls.
- A stopped origin produces a quick, uncached 5xx so the SPA can react within seconds.
- The origin and the control Lambda are unreachable except through this distribution.
- Basic traffic visibility through built-in CloudFront metrics and reports at no additional analytics cost.

**Non-Goals:**
- A custom domain as a requirement (optional section only).
- TLS on the private origin hop (no domain or origin certificate required for this change).
- Server-side rendering, image optimization, or any edge compute.
- Application-level rate limits (`demo-cost-controls`).
- Product analytics, campaign attribution, visitor/session tracking, wake-event storage, access-log analysis or frontend tracking integrations.

## Decisions

**1. Three origins, path-based behaviors.**

    /api/v1/control/*  → Lambda Function URL   (OAC, AWS_IAM, caching disabled)
    /api/v1/*          → EC2 private interface :8080 HTTP (VPC Origin, no custom secret, caching disabled)
    /*  (default)      → S3 bucket             (OAC, caching enabled)

Behavior order puts the more specific `control` path first. Control Lambda probes `https://<distribution-domain>/api/v1/health`, which matches the EC2 behavior and never invokes Lambda again; CloudFront reaches the backend through its private VPC Origin. Keep health responses and 5xx errors uncached. The `/api/v1` prefix means no URL rewriting function is needed.

Control-plane implementation is present locally as of 2026-11-24; deployment is
pending. Consume `WardrobeControlFunctionUrl` (and `WardrobeControlFunctionArn` if
needed) from `WardrobeControlPlane`. Create the edge with Lambda OAC, then run
`infra/bind-control.sh DISTRIBUTION_ID` to set the readiness URL and conditional
distribution-scoped Lambda permissions through a second control-stack deployment.
Do not add a reverse CloudFormation reference from control to edge. The helper
supplies plain parameters; control remains fail-closed until configured. All control
schedules follow deployment config `schedules_enabled`, default true.
The backend behavior allows `POST /api/v1/search`, forwards its JSON body and `Content-Type` unchanged, and disables caching for search. A CloudFront allowed-method preset may include other methods; backend routes remain unchanged. Verify body/header passthrough and uncached responses after deployment. Control permits GET/HEAD only; POST search goes to EC2 and does not require Lambda OAC payload hashing. Do not introduce request-body logging or analytics tracking.

- Alternative: separate subdomains per origin (needs a domain and CORS). Rejected.

**2. S3 private bucket with origin access control.**
Block all public access; the bucket policy admits only this distribution. The default root object is `index.html`; there is a single page, so no SPA fallback rule is required. Unknown paths return the S3 error mapped to `index.html` only if the frontend later adds client-side routes.

**3. Cache policy.**
Vite emits content-hashed files under `assets/`; they are uploaded with `Cache-Control: public, max-age=31536000, immutable`. `index.html` and images without hashes are uploaded with a short `max-age` (for example 60 s); the deploy invalidates `/index.html`. Unhashed images also use 60 seconds, avoiding a separate image behavior. AWS-managed `CachingOptimized` honors the 60-second/one-year S3 headers and excludes the viewer Host, cookies and query strings from the cache key. Its one-second minimum TTL is below both configured lifetimes; it must not be treated as supporting zero-TTL static responses. Do not use `UseOriginCacheControlHeaders` for S3: it forwards the viewer Host and can produce NotFound despite the object existing. No origin request policy is attached to the static behavior. Deployment uploads dependencies first, then index last, and invalidates both `/index.html` and `/`. Old hashed assets are retained for open tabs/rollback; cleanup is deliberate.

**4. Fast failure for a stopped origin.**
The `/api/v1/*` origin uses connection attempts = 1 and connection timeout of 2-3 seconds, response timeout of 30 seconds allowing the implemented [25-second backend search deadline](../2026-11-23-search-timeout-budget/design.md) to return its response within the frontend’s 30-second window under normal network conditions; verify the effective CloudFront setting instead of assuming a provider timeout bounds the whole search. CloudFront custom error TTLs are distribution-wide, not path-specific: set minimum TTL 0 for all configurable 5xx codes (500/501/502/503/504), without changing status/body or mapping errors to index.html. CachingDisabled has all cache TTLs zero on both API behaviors. S3 errors alone retain AWS's one-second minimum. The default error TTL is 10 seconds; leaving it enabled risks a stale 504 immediately after wake. A three-second connection timeout and 30-second response timeout allow the 25-second backend response under normal conditions, but are not an end-to-end browser guarantee.

**5. Private origin connectivity.**
The backend stack exports `WardrobeBackendVpcOriginId` and
`WardrobeBackendOriginPrivateDns`. Import the existing resource using
`cloudfront.VpcOrigin.fromVpcOriginAttributes` and bind with
`origins.VpcOrigin.withVpcOrigin`; do not create a second VPC Origin or use a public
`HttpOrigin`. The EC2 security group admits 8080 only from AWS's VPC-origin service
security group, not the shared CloudFront prefix list. Do not share the origin across
accounts. The Lambda Function URL separately retains IAM auth and OAC.

No origin custom authentication header or application origin filter is needed.
EC2 retains a public IP and Internet Gateway route for outbound API calls; unsolicited
public inbound requests are blocked. The backend hop uses private AWS connectivity.
The backend stack can create its VPC Origin before this distribution is created;
EC2 must be running for origin creation. Migration removes the old prefix-list rule
before activating the filter-free image. See the host runbook for deployment order.

**6. Pay-as-you-go without WAF (owner approved).**
Use ordinary pay-as-you-go distribution provisioning, without a pricing-plan
subscription or web ACL. Included Shield Standard remains; no paid Shield Advanced,
WAF rules, challenges, exclusions or block responses are introduced. Backend search
limits retain their existing 429 responses. Private-origin isolation and OAC do not
filter malicious application requests or cap visitor-triggered EC2 uptime. Adding
WAF later requires a separate protection and budget decision.

**7. Security headers.**
Attach the AWS-managed `SecurityHeadersPolicy` (ID `67f7725c-6f97-4210-82d7-5512b31e9d03`) to the default behavior without overrides or a custom policy. It supplies HSTS, nosniff, a referrer policy, `X-Frame-Options: SAMEORIGIN` and X-XSS-Protection. It does not supply CSP or `frame-ancestors`; neither is a requirement of this migration. No CloudFront Function or frontend workaround is introduced to recreate custom headers. See [AWS managed response headers policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-response-headers-policies.html#managed-response-headers-policies-security).

**8. Built-in CloudFront metrics and reports only.**
Use the distribution's default Monitoring metrics (requests, bytes transferred and aggregate 4xx/5xx error rates) and available console reports (usage, popular objects, referrers and viewers). These need no analytics infrastructure, frontend code or access-log delivery. Default metrics are included at no additional cost. Use only reports available without a paid upgrade; do not enable additional paid metrics, custom dashboards, log delivery or analytics services.

This provides rough traffic visibility, not unique visitors, sessions, conversions, campaign `?ref=` attribution, exact search outcomes or actual wake counts. Requests include assets, bots and control/health probes; reports can be delayed. Do not add a reporting pipeline to fill these gaps. No log bucket, analytics database, custom queries or tracking scripts are part of this migration.

Verified against AWS documentation on 2026-11-23: [default metrics and paid additional metrics](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/viewing-cloudfront-metrics.html), [console reports without access logs](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/reports.html), and [usage reports requiring no additional configuration](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/usage-charts.html).

**9. Optional custom domain section.**
When the owner decides on a domain: ACM public certificate in us-east-1 (DNS validation), alternate domain name on the distribution, DNS alias record (Route 53 or the registrar's DNS), then optionally HTTPS to the origin (Caddy or similar on the host with a DNS-challenge certificate) and removal of the plain-HTTP exception. None of the other tasks depend on it.


## Risks / Trade-offs

- [Usage or extra wake hours exceed €10] → Pay-as-you-go has no overage cap. Check account/organization allowance and actual costs; budget alerts notify but do not enforce a ceiling.
- [HTTP backend hop] → Use the VPC-origin private path, never the public IP. Viewer traffic remains HTTPS; backend TLS can be added separately.
- [Cached 504 after wake] → Distribution-wide 5xx minimum TTL 0 and API caching disabled; test explicitly.
- [Search retries exceed the browser timeout] → The shared search-timeout budget is implemented locally; verify CloudFront lets its 25-second backend response through; independent per-call retry settings are insufficient.
- [Bots and probes inflate request counts and may wake the instance] → Treat reports as approximate traffic information, not human visits; the instance cost ceiling remains the 24/7 case.
- [Unwanted API/wake traffic] → No WAF application filtering. Existing search limits remain; uptime can reach the 24/7 cost ceiling. Default CloudFront reports do not establish human visitor counts.

## Migration Plan

1. Deploy the host migration and control stack with schedules set by `schedules_enabled` (default true). Keep EC2 running while provisioning its VPC Origin and the edge association.
2. Deploy `WardrobeEdgeHosting` with all three behaviors. The edge imports host exports and the control URL; it creates no reverse control reference or duplicate VPC Origin.
3. Run `infra/bind-control.sh DISTRIBUTION_ID` to configure readiness and distribution-scoped Lambda permissions. The control URL remains fail-closed before binding. The Lambda OAC is explicitly attached to an HTTPS HttpOrigin for the Function URL; invocation permissions remain in the control stack, avoiding duplicate grants from the CDK FunctionUrlOrigin convenience helper.
4. Run `infra/deploy-spa.sh` to build Vite, upload with cache headers and invalidate entry paths. The full `infra/deploy.sh` wrapper performs these stages after host activation and stops EC2 by default even on ordinary failure. The wrapper explicitly applies `schedules_enabled` on every deployment (default true), independent of `stop_after_deploy`.
5. Perform live stopped/running/wake/search/isolation checks and record evidence. Nothing in local synthesis establishes live acceptance. Optional domain/TLS work remains deferred.
6. Rollback: publish a known-good build (retained hashed assets remain), or disable the distribution. S3 is retained on stack deletion without an automatic cleanup resource; manually remove it only when no longer needed.

## Open Questions

- How much recurring CloudFront allowance remains in the account/organization? Confirm before deployment and review actual week-one costs.
- Custom domain: decided later by the owner.
- `infra/` implements the host, control plane and edge in CDK TypeScript. None of the edge live checks have run.
- Host region is eu-central-1; the certificate for a future domain must be in us-east-1 regardless.

## Backend integration status — 2026-11-23

Repository code now defines the EC2 VPC Origin and exports its ID/private DNS;
this revision has not been deployed. The earlier public-origin deployment is
historical evidence only. Edge routing is now implemented locally; external isolation and end-to-end
readiness/search tests remain pending deployment. The owner's total target is €10/month:
retain the existing outbound EIP, introduce no NAT Gateway, and account for edge
usage and provisioning-time custom-resource API/Lambda calls before deployment.
