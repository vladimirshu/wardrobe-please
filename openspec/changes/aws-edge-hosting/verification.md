# Edge hosting verification

**Current decision:** on 2026-11-24 the owner approved pay-as-you-go without WAF
and requested specification updates plus implementation. This supersedes the
historical blocker and unselected evaluation below. Deployment/live acceptance
remain pending; see the implementation record at the end.

## 2026-11-24 — apply blocked at task 1.1

Current official sources:

- [Plan features](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/flat-rate-pricing-plan.html): Free includes 1 million requests/month, managed response-header policies, S3 OAC and WAF, but excludes VPC origins. An associated WAF web ACL is required for flat-rate plans.
- [Pricing](https://aws.amazon.com/cloudfront/pricing/): Business is $200/month and is the first flat-rate tier with VPC origins.

This contradicts the required Free-plan/private-origin combination and the
€10/month total AWS target. No pricing or security fallback was selected.
Task 1.1 remains incomplete: console verification, remaining timeout/error settings
and a chosen fallback are outstanding. No infrastructure implementation or AWS
mutation was performed during this apply attempt. Existing host/control code and
deployment status are unchanged.

Resume after an owner decision. Candidate for investigation: pay-as-you-go
CloudFront retaining the private origin, with separately assessed WAF costs and
revised protection requirements. This is a proposal, not an approved design.

## 2026-11-24 — initial pay-as-you-go evaluation (historical)

Pay-as-you-go supports the required VPC Origin without a separate VPC-origin
fee ([AWS announcement](https://aws.amazon.com/about-aws/whats-new/2024/11/amazon-cloudfront-vpc-origins/)).
The three-origin architecture, Lambda/S3 OAC, managed security headers and private
backend path can be retained. This is a pricing alternative, not a deployed result.

[CloudFront pricing](https://aws.amazon.com/cloudfront/pricing/pay-as-you-go/)
includes recurring 10 million requests and 1 TB outbound delivery monthly, plus
1,000 invalidation paths. Allowances are shared at account/organization level;
remaining allowance has not been checked. AWS-origin fetch transfer is free;
viewer POST data forwarded to an origin is billed ($0.02/GB in Europe).
For 10,000 bodies of 4 KiB this is about $0.00076. S3 storage/requests, control
Lambda, Scheduler and operational usage remain separate. No flat-rate WAF bundle
applies and there is no flat-rate no-overage guarantee.

[WAF pricing](https://aws.amazon.com/waf/pricing/) through 2027-01-31 is
$5/web ACL/month + $1 per ordinary managed rule-group reference + $0.60/million
requests. One group and 10,000 requests cost $6.006/month; three groups cost
$8.006. These examples exclude premium subscriptions, Marketplace groups,
extra capacity/body inspection, challenges and logs. An empty ACL is not useful
application protection. There is no automatic equivalent of the Free-plan default
protection: selecting a paid WAF configuration requires a separate decision.

The pricing page explicitly schedules replacement rates from **2027-02-01**:
$0.01/protected-resource-hour + $0.60/million base requests; ordinary groups
have no separate fee. At 730 hours and 10,000 requests this is $7.306/month.
The [2026-11-23 announcement](https://aws.amazon.com/about-aws/whats-new/2026/11/aws-waf-introduces-simplified-pricing/)
instead says available immediately. Use the explicit billing effective date for
this estimate, and reconfirm before provisioning.

### Monthly estimate, not an account bill

Reuse the [runbook's 2026-11-23 host estimate](../../../infra/RUNBOOK.md):
308 EC2 hours, 8 GB gp3, 730 EIP hours and 3 GB ECR = $7.6684 before tax.
Add $0.50 operational headroom and a provisional $0.10 small-SPA S3 allowance
(not a measured charge or guaranteed upper bound). Assume CloudFront delivery
fits the remaining recurring allowance, no custom domain, no paid metrics/logs,
and light control-plane use within that operational headroom.

| Scenario | Total USD before tax | EUR before tax | EUR with illustrative 19% VAT |
|---|---:|---:|---:|
| Pay-as-you-go, no WAF | 8.27 | 7.56 | 8.99 |
| One ordinary managed WAF group, current rates | 14.27 | 13.05 | 15.53 |
| Three ordinary managed WAF groups, current rates | 16.27 | 14.88 | 17.70 |
| WAF base protection, rates from February 2027 | 15.57 | 14.24 | 16.94 |

Python-calculated totals use the latest published [ECB reference rate](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html)
observed: 2026-11-23, EUR 1 = USD 1.0939. AWS invoice FX and actual tax treatment
can differ. Host prices are the existing runbook estimates, not refreshed account
pricing. No AWS account APIs, billing queries or mutations were performed here.
OpenAI/Qdrant charges remain outside this AWS estimate. Additional wake hours,
deployments, other account resources and excess traffic can exceed the allowances.

### Recommendation and decision still required

For the strict €10 target, pay-as-you-go without WAF is the only evaluated option
that fits the scheduled-host estimate, with little margin. Retain private-origin
isolation, S3/Lambda OAC, AWS-managed security headers, included Shield Standard
and existing backend search limits. This omits WAF application-request filtering;
search limits do not protect every route or cap visitor-triggered host uptime.
It requires explicitly revising the edge-protection spec before implementation.

If WAF is required, budget approximately €16–18/month for these scenarios instead,
and choose the rule groups explicitly. No fallback, WAF removal or budget increase
has been approved by the evaluation request. All apply tasks remain pending.


## 2026-11-24 — approved implementation, not deployed

The owner selected pay-as-you-go without WAF. Proposal, design, delta specs,
architecture and runbook now reflect that decision. Historical pricing evaluation
above remains the evidence for the decision, not a continuing approval blocker.

Implemented `infra/lib/edge-hosting-stack.ts`: retained private/encrypted S3 bucket,
S3 OAC and signed Lambda OAC; ordered control/backend/default routing, imported
VPC Origin, managed security headers, managed cache/origin-request policies,
backend one attempt / three-second connection / 30-second response timeout,
and zero minimum TTL for configurable 5xx errors. No WAF, plan subscription,
second VPC Origin, analytics resources, edge functions or custom header policy.
Control permissions stay in the parameterized control deployment, avoiding reverse
references and duplicate grants. The host's existing managed-SG-only rules remain.

`infra/deploy-spa.sh` resolves stack outputs, builds the locked Vite frontend,
uploads dependencies before index with immutable/60-second headers, retains old
hashed assets and creates invalidations for both entry paths. The full host
wrapper now deploys edge, binds control and publishes the frontend before its
existing default EC2 shutdown. Existing schedule state is retained; fresh schedules
remain disabled. Failures do not claim atomic rollback or live acceptance.

Additional official configuration sources checked on 2026-11-24:

- [Origin settings](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistValuesOrigin.html): configure connection attempts/timeouts separately from response timeout; these are not a browser end-to-end guarantee.
- [Error cache TTL](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/custom-error-pages-expiration.html): default is 10 seconds; zero minimum TTL still has a one-second floor for S3 origins only. Error settings are distribution-wide, without path-level mappings.
- [Managed cache policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-cache-policies.html): CachingDisabled gives API TTLs of zero; UseOriginCacheControlHeaders honors the SPA cache headers.
- [Lambda OAC](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-lambda.html): AWS_IAM and distribution-scoped permissions are required. Control uses GET/HEAD; search POST goes to EC2, so Lambda's signed-body hash requirement does not affect search.

Offline checks:

- Infrastructure TypeScript compilation and production Vite build passed.
- Strict CDK synthesis passed for all three stacks with `--no-lookups` and explicit
  eu-central-1 environment, using checked-in context. An initial invocation inherited
  the CLI us-east-1 default; explicit AWS_REGION/AWS_DEFAULT_REGION resolved it
  without account lookups. No source configuration change was needed.
- 15 Node tests passed, including synthesized security/routing/cache/timeout assertions.
- 12 Python deployment tests passed, including publication order/cache headers,
  failure before publication, full deployment binding order and default host cleanup.
- IntelliJ build reported success, no problems; TypeScript/CloudFormation checks
  use the dedicated CLI tests because the IDE project modules are Java.

No AWS deployment, account allowance inspection, budget verification, real-provider
search or live origin isolation was performed. Remaining mandatory live acceptance
and week-one billing tasks stay unchecked. Optional domain/origin TLS remain deferred.
