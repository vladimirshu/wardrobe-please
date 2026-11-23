## ADDED Requirements

### Requirement: Lean production dependencies
The production artifact SHALL exclude the OAuth2 client starter and AspectJ starter/weaver introduced solely for the existing search timer. Spring Security, Actuator/readiness, input validation, OpenAI embeddings and Qdrant functionality SHALL remain.

#### Scenario: Default packaged application
- **WHEN** the backend is built for the default OpenAI deployment
- **THEN** its runtime classpath omits unused OAuth2 login dependencies and the removed AspectJ timer implementation, and normal startup/readiness/search remain functional

### Requirement: Programmatic search timing
The backend SHALL preserve the `product.search` latency metric through programmatic Micrometer timing, including successful and exceptional completion, without the TimedAspect bean or `@Timed` annotation. It SHALL NOT add raw message or conversation values as metric tags.

#### Scenario: Search fails
- **WHEN** a search throws an exception
- **THEN** its duration is recorded and the existing exception behavior is preserved

### Requirement: Optional DeepSeek runtime
The DeepSeek starter declaration SHALL be commented out in the default build and DeepSeek SHALL NOT be packaged or loaded by the default production runtime. Default compilation and OpenAI startup SHALL succeed without DeepSeek runtime classes. Provider-specific source and tests SHALL be retained with a documented re-enablement path: uncomment the starter, rebuild, select DeepSeek and supply its key. DeepSeek-required tests SHALL run only with its runtime explicitly enabled.

#### Scenario: Default OpenAI deployment
- **WHEN** the starter is commented out and the application is packaged and started
- **THEN** OpenAI search and embeddings work without DeepSeek on the production classpath

#### Scenario: Re-enable DeepSeek
- **WHEN** the starter is uncommented, the backend rebuilt and DeepSeek selected with a valid configuration
- **THEN** DeepSeek chat is available and embeddings still use OpenAI

#### Scenario: Missing optional runtime
- **WHEN** DeepSeek is selected without its runtime dependency
- **THEN** startup fails with a clear configuration message rather than silently switching providers or reporting an unexplained missing class

### Requirement: Measured memory acceptance
Memory results SHALL distinguish used/committed heap and total process/container memory, use a packaged Java 17 baseline under comparable settings, and cover startup and repeated search. A 512 MiB host SHALL be selected only after total-host measurements include OS/agent overhead and show acceptable headroom, startup and latency without OOMs or sustained swap pressure. Otherwise the documented larger-instance fallback SHALL remain.

#### Scenario: Low heap but excessive total memory
- **WHEN** the application meets its heap cap but leaves insufficient host memory or relies on sustained swapping
- **THEN** it is not declared suitable for a 512 MiB host


### Requirement: Shared bounded runtime
The application SHALL use shared `-Xms64m -Xmx256m` limits for Docker and bootRun,
with documented equivalent direct/IDE launches. It SHALL NOT explicitly select a
garbage collector or cap metaspace. Build/test JVM settings SHALL be independent.
The single Compose backend profile SHALL have equal 640 MiB memory and
memory-plus-swap limits, disabling container swap. AWS SHALL evaluate micro,
with small as fallback; total-host acceptance SHALL require measurements.

#### Scenario: Starting the backend
- **WHEN** the backend runs through the standard image or bootRun
- **THEN** it uses the shared 64 MiB initial and 256 MiB maximum heap, with JVM-default GC

#### Scenario: Container boundary
- **WHEN** the backend runs through Compose
- **THEN** it is capped at 640 MiB without swap; local frontend/Qdrant memory is additional
