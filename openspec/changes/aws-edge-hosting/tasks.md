## 1. Verification first

Owner decision on 2026-11-24 supersedes the rejected Free flat-rate assumption:
pay-as-you-go without WAF, retaining private origins, OAC and search limits.
Checked tasks below describe repository implementation and offline verification,
not deployed resources. See [verification](verification.md).

- [x] 1.1 Verify published pricing/features, managed headers, origin attempt/timeout settings and error caching; record the selected pay-as-you-go/no-WAF fallback and its cost limits
- [ ] 1.2 Before deployment, confirm actual account/organization recurring allowance availability and pay-as-you-go configuration in AWS; keep schedules disabled until acceptance

## 2. Buckets and distribution

- [x] 2.1 Define the private SPA bucket with block-public-access and S3 OAC, without analytics log buckets
- [x] 2.2 Define all three origins/ordered behaviors: control Function URL via signed OAC (GET/HEAD, uncached), backend via imported private VPC Origin (POST allowed, JSON body/Content-Type forwarded, uncached, one attempt, three-second connection timeout), and default S3
- [x] 2.3 Set distribution-wide configurable 5xx error minimum TTLs to zero without status/body remapping, API cache TTLs to zero, and backend response timeout to 30 seconds for its 25-second deadline; live timing remains in 4.2
- [x] 2.4 Attach AWS-managed SecurityHeadersPolicy without custom policy, CSP, overrides or edge functions
- [x] 2.5 Import the host VPC Origin ID/private DNS without public HttpOrigin, origin secret or second VPC Origin; preserve the documented managed-SG-only migration sequence; live isolation remains in 3.2

## 3. Edge protection

- [x] 3.1 Use pay-as-you-go without WAF, plan subscription or paid Shield; retain OAC/private-origin restrictions and backend search limits; document absence of application filtering/wake spending cap and assert configuration offline
- [ ] 3.2 After deployment, verify managed-SG-only 8080 ingress, no public/shared-prefix-list access or cross-account origin sharing; test anonymous direct EC2, Function URL and S3 access rejection; only then retire the historical secret out of band

## 4. SPA deployment

- [x] 4.1 Add SPA deployment with long immutable assets, 60-second index/unhashed files, index last, retained old assets and invalidation of `/index.html` and `/`; integrate host → control → edge → binding → SPA in the full deployment
- [ ] 4.2 Deploy and bind the actual control health URL; check the default CloudFront domain with EC2 stopped/running: fast uncached 5xx, STARTING → UP without stale 504 or recursion, page load, unchanged JSON POST/body/Content-Type, uncached responses and backend deadline within the normal browser window; no WAF is configured

## 5. Built-in traffic visibility

- [x] 5.1 Document console Monitoring/Reports, delayed data and request counts versus human visits; no analytics enablement or custom reports
- [ ] 5.2 After deployment/data arrival, confirm default metrics/reports show traffic and no access-log delivery, analytics storage, tracking scripts or paid metrics are enabled

## 6. Optional: custom domain (deferred until selected)

- [ ] 6.1 Request ACM in us-east-1, add alternate domain and DNS alias; verify existing behavior
- [ ] 6.2 Optionally configure backend TLS and update the host VPC Origin/SG; remove the plain-HTTP exception only after verification

## 7. Runbook and cost

- [x] 7.1 Extend infra/RUNBOOK.md for SPA deployment/invalidation, private-origin and SG inspection, monitoring/reports, rollback and distribution disablement
- [ ] 7.2 Confirm the AWS Budgets alert exists; after the first operating week record CloudFront/S3/control/host actuals and absence of WAF charges against the estimate
