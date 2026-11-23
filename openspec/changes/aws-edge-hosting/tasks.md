## 1. Verification first

- [ ] 1.1 Verify against the current CloudFront free flat-rate plan documentation and console: included requests, default WAF protection, AWS-managed SecurityHeadersPolicy, origin timeout/attempt settings, custom error caching, custom domain; record the answers and the chosen fallbacks in the design

## 2. Buckets and distribution

- [ ] 2.1 Create the private SPA bucket (block public access, origin access control) with no analytics log bucket
- [ ] 2.2 Create the distribution with three origins and the behaviors `/api/v1/control/*` (Lambda, OAC, caching disabled), `/api/v1/*` (EC2 HTTP :8080, custom header `X-Origin-Verify`, caching disabled, 1 attempt, 2-3 s connect timeout), default `/*` (S3); allow POST on the backend behavior and forward the JSON body and Content-Type unchanged
- [ ] 2.3 Configure error handling for `/api/*` so 5xx responses are not cached (minimum TTL 0); confirm the response timeout allows the planned 25-second backend deadline response within the frontend’s 30-second window under normal network conditions
- [ ] 2.4 Attach AWS-managed `SecurityHeadersPolicy` (`67f7725c-6f97-4210-82d7-5512b31e9d03`) to the default behavior; create no custom policy, CSP, header overrides or edge-function workaround
- [ ] 2.5 Generate the origin secret once, store it in SSM (shared with `aws-backend-host`; the control Lambda does not receive it), and keep it out of the repository

## 3. Edge protection

- [ ] 3.1 Retain the selected plan’s default WAF protection and required associated web ACL; add no custom rules, path exclusions, challenges or block responses. Verify page loads, POST search and automated health probes work without customization; record any incompatibility for a separate decision. Keep backend rate limits unchanged
- [ ] 3.2 Test that direct calls to the EC2 address, to the Function URL and to the bucket URL are rejected

## 4. SPA deployment

- [ ] 4.1 Add a deploy script/target: sync the Vite build to the bucket with long immutable cache for `assets/`, short cache for `index.html` and unhashed images, invalidate `/index.html`
- [ ] 4.2 End-to-end check on the `*.cloudfront.net` name with the instance stopped: fast 5xx on `/api/v1/health` (not cached), `/up` wakes it, Lambda probes health through CloudFront and returns UP without a stale 504 or recursive invocation, POST JSON search works with unchanged body and Content-Type and uncached responses; verify POST works with default WAF protection and document its default block response without remapping it to 429; configure Lambda's health URL after distribution creation

## 5. Built-in traffic visibility

- [ ] 5.1 Document CloudFront console Monitoring and Reports in the runbook, including delayed data and request counts versus human visits; no analytics enablement or custom reports are required
- [ ] 5.2 After deployment, confirm default metrics and available reports show traffic once data arrives; verify no access-log delivery, analytics storage, tracking scripts or additional paid metrics were enabled

## 6. Optional: custom domain (only when decided)

- [ ] 6.1 Request the ACM certificate in us-east-1, add the alternate domain name and DNS alias; verify nothing else changes
- [ ] 6.2 Optionally add HTTPS to the origin (certificate on the host) and remove the plain-HTTP exception from the design

## 7. Runbook and cost

- [ ] 7.1 Extend `infra/RUNBOOK.md`: deploy SPA, invalidate, rotate the origin secret (SSM, host, distribution; Lambda holds no origin secret), view built-in metrics and reports, disable the distribution
- [ ] 7.2 Cost verification: check the AWS Budgets alert exists; after the first week review Cost Explorer for CloudFront, S3 and WAF lines against the plan assumptions and record actual figures
