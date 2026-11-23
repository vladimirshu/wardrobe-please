## Decision

Use the existing request DTO as a validated JSON body for `POST /api/v1/search`:

```http
POST /api/v1/search
Content-Type: application/json

{"message":"blue cotton jacket","conversationId":"session_42","clearMemory":false}
```

Move all three existing fields together rather than splitting body and query binding. Keep the current normalization, validation and false default for omitted `clearMemory`. Search fields come only from the body; query parameters do not supply missing fields or override body values. GET on this path returns 405 and never invokes search. No compatibility route or redirect is needed for this prototype; deploy frontend and backend together.

Reuse response handling, search admission and conversation logic. Invalid JSON, an absent body and invalid fields return 400 before admission or downstream calls; unsupported content types return 415. Preserve existing field-error responses for validation failures. The frontend sends JSON to the bare search path and keeps its current timeout, cancellation and error handling. Health, browsing and control methods are unchanged.

The planned CloudFront API behavior must allow POST and forward the body and Content-Type without caching search. CloudFront method presets may allow additional methods; that does not add backend routes. Keep existing path-based WAF protection applicable to POST search. Traffic visibility follows AWS edge hosting: built-in CloudFront metrics and available reports only, without access-log delivery or campaign tracking. Do not introduce request-body logging.

## Scope and documentation

The backend and storefront now implement this transport. AWS edge deployment remains planned. The implementation updates OpenAPI, active backend/frontend specifications and examples that name GET search, plus relevant active OpenSpec designs (including the pending MCP search design if it references the transport). Preserve archived specifications as historical records. The current architecture diagram shows POST JSON transport and its pre-admission errors.

## Verification

Cover JSON binding and normalization, missing/invalid body or fields, omitted clearMemory, rejection of GET, unchanged 200/400/422/429/500 responses and quota behavior. Verify the storefront sends no search fields in the URL, sends the expected JSON body and handles existing responses. Verify POST forwarding in the AWS edge deployment check when that infrastructure is implemented. Moving fields out of the URL does not redact arbitrary query parameters or explicitly logged bodies.
