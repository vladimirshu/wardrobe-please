## ADDED Requirements

### Requirement: Built-in traffic visibility
The migration SHALL rely only on default CloudFront distribution metrics and console reports available without additional analytics charges. No analytics-specific enablement SHALL be required. The runbook SHALL identify Monitoring and Reports in the CloudFront console.

#### Scenario: Inspect traffic
- **WHEN** the operator views the deployed distribution after traffic data becomes available
- **THEN** default metrics show requests, bytes transferred and aggregate error rates without access-log delivery or frontend tracking

### Requirement: No additional analytics infrastructure or charges
The migration SHALL NOT enable access-log delivery, provision analytics log buckets or databases, add tracking scripts, implement custom analytics queries, enable additional paid metrics or custom dashboards, or require a paid plan upgrade for analytics. Application error logging and bounded backend operational logs are outside this analytics restriction.

#### Scenario: Deployment configuration
- **WHEN** the infrastructure and frontend deployment are reviewed
- **THEN** no analytics resources or tracking integrations are configured and additional paid CloudFront metrics remain disabled

### Requirement: Approximate traffic information only
The documentation SHALL explain that request counts include assets, bots and probes, reports may be delayed, and built-in traffic information is not unique-visitor or session analytics. Campaign-tag attribution, exact search outcomes and actual wake-event counts SHALL NOT be required.

#### Scenario: Report unavailable or insufficient
- **WHEN** a desired report is unavailable without an upgrade or cannot answer a product-analytics question
- **THEN** default metrics remain sufficient for this migration and no paid feature or custom reporting pipeline is added
