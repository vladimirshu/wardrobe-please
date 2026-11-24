# availability-schedule Specification

## Purpose
Define shared opening hours, weekday scheduling, off-hours shutdown and deployment-controlled enablement.

## Verification status
Synced from the implemented change on 2026-11-24 at the owner's request. Live
acceptance and outstanding operational/cost checks remain unverified and will be
performed by Vladimir. Archiving is not verification. See [remaining tasks](../../changes/archive/2026-11-24-aws-control-plane/tasks.md)
and [verification record](../../changes/archive/2026-11-24-aws-control-plane/verification.md).

## Requirements
### Requirement: Weekday opening window
The system SHALL start the backend instance on the configured opening days at the configured opening time and stop it on those same configured opening days at the configured closing time. The checked-in application configuration SHALL provide the IANA time zone so both schedules follow daylight-saving changes. The current configuration opens Monday to Friday at 08:00 and closes Monday to Friday at 22:00 in Europe/Berlin. Neither recurring start nor recurring stop SHALL run on Saturday or Sunday; the backend SHALL stay stopped by default at weekends, while visitor-triggered wake remains available.

#### Scenario: Weekday morning
- **WHEN** it is 08:00 on a Wednesday in the configured zone
- **THEN** the instance is started if it is not already running

#### Scenario: Weekday evening
- **WHEN** it is 22:00 on a configured opening day in the configured zone
- **THEN** the instance is stopped if it is running

#### Scenario: Weekend
- **WHEN** it is Saturday or Sunday and nobody has woken the instance
- **THEN** the instance stays stopped

#### Scenario: Missing time zone
- **WHEN** the stack is deployed with a missing or invalid IANA time zone in the application configuration
- **THEN** the deployment fails with a clear message

### Requirement: Twenty-minute off-hours session
When the instance is running outside the opening window, the system SHALL stop it once it has been running for at least 20 minutes, checking at least every 5 minutes. The opening window used by this check SHALL be the same configuration as the schedules.

#### Scenario: Wake at night
- **WHEN** a visitor wakes the instance at 23:00 and nobody else acts
- **THEN** the instance is stopped between 20 and 25 minutes after it started

#### Scenario: Wake during opening hours
- **WHEN** the instance is running inside the opening window for any length of time
- **THEN** the sweeper does not stop it

#### Scenario: Wake shortly before closing
- **WHEN** the instance is woken at 21:50 on a weekday
- **THEN** it is stopped by the 22:00 schedule

#### Scenario: Wake again after a session
- **WHEN** a visitor wakes the instance again after the 20-minute session ended
- **THEN** a new 20-minute session starts from the new launch time

#### Scenario: Weekend wake across 22:00
- **WHEN** a visitor wakes the instance at 21:50 on Saturday or Sunday
- **THEN** no scheduled 22:00 stop occurs and the off-hours sweeper stops it 20–25 minutes after its session started

### Requirement: No heartbeat dependency
Starting and stopping SHALL NOT depend on any signal from visitors other than calls to the wake endpoint.

#### Scenario: Active visitor at the end of a session
- **WHEN** a visitor is searching when the 20-minute session expires
- **THEN** the instance is stopped regardless

### Requirement: Shared frontend schedule
The frontend build, schedules and sweeper SHALL derive the opening days, times and IANA time zone from the same checked-in application configuration. The opening interval SHALL include its start and exclude its end in that zone. Schedule changes SHALL include rebuilding and deploying the frontend with the updated configuration.

#### Scenario: Matching configuration
- **WHEN** the stack and frontend are built for a deployment
- **THEN** their opening days, times and IANA time zone match, including daylight-saving rules

#### Scenario: Schedule change
- **WHEN** the opening window or time zone changes
- **THEN** the corresponding frontend build receives the updated configuration and is deployed with the schedule change

### Requirement: Deployment-configured schedule enablement
The full deployment SHALL read `schedules_enabled` from its JSON configuration,
default to true when omitted, reject non-boolean values, and explicitly apply it
to the control stack's `SchedulesEnabled` parameter on every deployment. The setting
SHALL control all three schedules and SHALL be independent of `stop_after_deploy`.
Fresh direct CDK deployments SHALL also default to enabled schedules.

#### Scenario: Default enablement
- **WHEN** deployment config omits `schedules_enabled`
- **THEN** deployment explicitly enables weekday start, weekday stop and the sweeper

#### Scenario: Deliberate disablement
- **WHEN** deployment config sets `schedules_enabled` to false
- **THEN** deployment explicitly disables all three schedules, including on an existing stack

#### Scenario: Invalid value
- **WHEN** deployment config contains a string instead of a boolean for `schedules_enabled`
- **THEN** deployment fails before any AWS operation

