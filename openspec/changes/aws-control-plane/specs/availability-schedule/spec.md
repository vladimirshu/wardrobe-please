## ADDED Requirements

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
