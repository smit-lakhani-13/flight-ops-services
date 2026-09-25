# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: **Security → Report a
vulnerability** on this repository. It opens a private advisory that only the
maintainer can see. A public issue tells everyone at once.

If that button is missing, the setting is off. Then open a public issue with
one sentence and no detail: "I have a security finding, please open a private
channel." Do not put the finding in it. The fallback gives a reporter an option
between full disclosure and silence.

Please include what you did, what happened and what you expected. A proof of
concept helps. A working exploit is not required.

Supported: `main`. There is no support branch for older tags.

## What this is

A demonstration service. It holds seeded data and has never processed a real
booking. It has run only on a laptop and in CI. The AWS path is written and
linted, and the deploy job is gated off.
[doc/DEPLOYMENT.md](doc/DEPLOYMENT.md) opens with an `Executed on:` line that
will carry the date if that changes.

Some decisions below suit a demo and would be wrong for a system with real
users. Each one is marked where it comes up.

## Authentication and authorisation

There are two ways in and one rule set. HTTP Basic is always on. JWT bearer
tokens are accepted when `spring.security.oauth2.resourceserver.jwt.issuer-uri`
is set, and the startup log says which mode is active. Both feed the same
`authorizeHttpRequests` block, so a rule can be wrong in only one place.
Spring's default `JwtGrantedAuthoritiesConverter` maps a token's `scope` claim
to authorities prefixed `SCOPE_`. A token carrying
`scope: "flights:read flights:write"` therefore holds the same strings the
`api` user holds. `SecurityConfig` registers the JWT support only when a
`JwtDecoder` bean exists. See
[adr/0005](adr/0005-one-rule-set-for-basic-and-jwt.md).

**Warning:** if you enable JWT, set the audience too. `issuer-uri` alone
validates the signature, the issuer and the lifetime, and that is not enough.
An issuer mints tokens for every application registered with it, so a token
issued to another client of the same tenant arrives correctly signed and is
accepted. The audience claim is the only field that says which API the token
was for.

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: https://your-idp.example.com/
spring.security.oauth2.resourceserver.jwt.audiences: [flight-ops-service]
```

Both are Spring Boot properties. `audiences` installs a claim validator inside
the decoder, so a refactor of `config/SecurityConfig` cannot lose the check.
Neither is set today: no `JwtDecoder` bean exists, and the service logs
`No JwtDecoder configured` at startup. The two callers are machine identities
that do not need an authorisation server. `src/main/resources/application.yml`
repeats the block with the reasoning.

| Path | Who |
|---|---|
| `/actuator/health`, `/liveness`, `/readiness` | anyone. The kubelet has no credentials. Only `ROLE_OPS` sees the health components (`management.endpoint.health.roles: OPS`); everyone else gets the status and the group names |
| `/actuator`, `info`, `metrics`, `prometheus` (the other exposed endpoints) | `ROLE_OPS` |
| `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**` | anyone, for `GET` and `HEAD`. See below |
| `/error` | anyone. It is the container's own forward target and is not an API route |
| `/api/**` | `flights:read` for `GET` and `HEAD`; `flights:write` for `POST`, `PATCH` and `DELETE` |
| everything else | denied |

`/error` is open for a reason that is easy to get wrong. The container forwards
errors raised outside Spring MVC to `/error` after the security filter chain
has run. A URL the firewall rejects is one: today it gets a `400` in the usual
envelope, and with `/error` denied it would get a `401` about `/error`. MVC's
own 404s never reach `/error`, because `GlobalExceptionHandler` answers them
first. `exception/ApiErrorController` serves the path with the same
`ErrorResponse` envelope as everything else and a generic message. The
container's own error text can name an internal path or an exception class,
and neither belongs in a response.

### 401 and 403

No credentials, or credentials that do not verify, get `401 UNAUTHENTICATED`.
Valid credentials without the authority get `403 FORBIDDEN`. The filter that
rejects a password or token answers the 401 itself. Past that point, Spring's
`ExceptionTranslationFilter` picks between the two by whether the
authentication is anonymous. A 401 tells a client to retry with credentials. A 403 tells it
that retrying will not help. Collapsing the two sends a correct client into a
credential refresh loop over a permissions problem.

The 401's `WWW-Authenticate` challenge follows the credential that failed. A
rejected bearer token gets
`Bearer realm="flight-ops-service", error="invalid_token"`, which an OAuth
client reads to decide whether to refresh. No credentials, or a rejected
password, get `Basic realm="flight-ops-service"`. The body is the same
`UNAUTHENTICATED` JSON in both cases, and it never says whether the user
exists. `BearerTokenChallengeTest` checks both challenges in one context.

`security/JsonAuthenticationEntryPoint` and `security/JsonAccessDeniedHandler`
write both, through `ErrorResponseWriter`. It shares the container's
`ObjectMapper` and `Clock`, so the timestamp matches every other error. The
`@RestControllerAdvice` never sees these responses, because Spring Security
rejects the request before the `DispatcherServlet` runs. The 403's WARN names
the method and the path, never the principal or a header, so the log does not
become a second home for a credential.

I checked what happens without the two writers by swapping Spring's defaults
back in. Those call `sendError`, and the container forwards to `/error`.
`ApiErrorController` then answers with the right code and its generic message
about the method and the path, and a 403 leaves no log line.

### The OpenAPI document is public and the API is not

`/v3/api-docs` and Swagger UI are reachable without credentials. This is
deliberate and recorded in [adr/0012](adr/0012-openapi-public-read.md). The
document describes paths and schemas, and every operation still requires
authentication. Needing credentials to read about an API you cannot call helps
no one. Set `SWAGGER_UI_ENABLED=false` to serve the JSON without the UI.

What that concedes: an unauthenticated visitor learns the shape of the API. A
service on a private network could reasonably withhold that. For one on a
public URL, anyone can learn the same by reading this repository.

## Sessions and CSRF

`SessionCreationPolicy.STATELESS`, with CSRF disabled. There is no cookie, and
a bearer token is never attached by the browser. A Basic password can be, once
the browser's own login prompt has cached it. What closes that gap is the
request shape: every write needs a JSON body or is a `DELETE`, which a
cross-site form cannot send, and there is no CORS policy to let another site's
script through. Leaving CSRF on with no session produces a 403 on every POST
and a day of debugging. See [adr/0006](adr/0006-stateless-sessions-no-csrf.md).

If a browser-facing UI with cookie sessions is ever added, both decisions
reverse, and the ADR is where to start.

## Secrets

- **No credentials in the repository.** The default profile ships `{noop}`
  passwords for localhost. `application.yml` has no default for `DB_PASSWORD`
  in the `postgres` and `prod` profiles. A missing value stops startup without
  naming itself: the unresolved placeholder is sent as the password, and
  Flyway's first connection fails with `password authentication failed`. Read
  that as "is `DB_PASSWORD` set?" first.

- **Prefixed API passwords.** `API_PASSWORD` and `OPS_PASSWORD` must carry an
  `{id}` prefix, as in `{bcrypt}$2y$10$…`. Passwords go through a
  `DelegatingPasswordEncoder`, which reads the prefix to pick the algorithm.
  `{noop}dev-secret` locally and a `{bcrypt}` hash in a deployment therefore
  verify in the same build. Moving to another algorithm becomes
  a per-user data migration, with no flag day. `{argon2}` and `{scrypt}` also
  need `org.bouncycastle:bcprov-jdk18on`, which this build does not include.

- **Two startup checks.** `ApiSecurityProperties` rejects a missing or
  unprefixed value when the properties are bound, naming the property and the
  variable: `app.security.api-password (API_PASSWORD)`. `SecurityConfig` then
  asks the encoder to verify each value once. An id the encoder does not know,
  such as `{BCRYPT}` or a misspelt `{bcrpyt}`, stops startup there. The log
  names the property, then gives the encoder's reason:
  `app.security.api-password cannot be verified by the configured DelegatingPasswordEncoder`.
  An argon2 or scrypt hash stops it the same way, on a
  `NoClassDefFoundError`. Without the checks, the pod would report itself healthy
  and answer every login as that user with a 500. The id is case-sensitive, so
  write `{bcrypt}`. `PasswordVerifiabilityTest` covers the self-check. Its
  limit is listed under [Known limitations](#known-limitations).

- **No password in the log.** The prefix check throws from the
  record's constructor, and its message ends with `API_PASSWORD is not set`
  (or `OPS_PASSWORD is not set`) or `the value is not shown`. Bean Validation would have printed the rejected
  value on a `Value:` line of Boot's startup report, so a plaintext password
  set without its prefix would have reached the pod log. For an unknown id,
  the self-check's message names the id and not the hash.

- **Generated passwords.** `deploy/aws/up.sh` generates three passwords with
  `openssl rand`. The database password goes, unhashed, into the RDS stack (a
  `NoEcho` parameter) and the Kubernetes Secret, and is never printed. The API
  and ops passwords are hashed with `htpasswd -bnBC 10`, and only the
  `{bcrypt}` hashes go into the Secret. Their plaintext is printed twice: at
  step 9, as soon as the Secret exists, and in the closing summary. None of the
  three touches disk or reaches GitHub.

- **Secrets are only base64-encoded.** They are not encrypted: anyone with
  `get secret` on the namespace can read them, and `k8s/secret.example.yaml`
  says so in its header. The production answer is AWS Secrets Manager through
  the Secrets Store CSI driver. It is not installed here. That is a gap, not a
  trade-off.

- **No AWS access keys anywhere.** In the cluster the pods would use IRSA: a
  projected, short-lived token exchanged with STS. The deploy job would use
  GitHub's OIDC provider and short-lived STS credentials. The trust policy
  pins the audience to `sts.amazonaws.com` and the `sub` claim to
  `repo:owner/repo:ref:refs/heads/main`. A bare wildcard in `sub`, such as
  `repo:*` under `StringLike`, would let any repository on GitHub assume the
  role.

## Transport

Nothing is deployed, so nothing is on the internet today. This section
describes what `k8s/components/ingress` would create the day someone applies
it.

That Ingress listens on **port 80 with no TLS**. Basic credentials would cross
the internet base64-encoded, and anyone who captures them can decode them.

That is acceptable for a short-lived demo with generated throwaway passwords
and no real data, and for nothing else. There is no self-signed certificate,
because it trains people to click through the warning that exists
to stop them. The HTTPS recipe (ACM, DNS validation, three Ingress annotations
and `server.forward-headers-strategy`) is in
[doc/DEPLOYMENT.md §8](doc/DEPLOYMENT.md#8-http-and-what-https-would-take). The
work is written down. What is missing is a domain.

## Data exposure

- `BookingDto` does **not** carry `idempotencyKey`. It used to. An idempotency
  key is a client's private token, and leaking it on a list endpoint would let
  any reader replay another client's booking.

- `passengerName` **is** returned on the list endpoint. That is a scope
  decision: this is an internal operations API, and the operator is
  entitled to see who is on the flight. The field is in the OpenAPI schema for
  the response, so no one has to guess. `BookingWriter`'s log line leaves the
  name out, because it is personal data, and the event carries no name.

- `passengerName` refuses control characters, with the message
  `must not contain control characters`. PostgreSQL refuses a NUL in a text
  column. The request fingerprint also joins its fields with U+001F, and a
  separator inside the name could make two different requests hash the same.
  `BookingControllerTest` covers both.

- The writes read JSON only. swagger-core puts a YAML reader on the classpath,
  and none of the `spring.jackson` settings reach it, so each `POST` and `PATCH`
  declares `consumes = application/json`. One sent with any other
  `Content-Type`, or none, gets `415 UNSUPPORTED_MEDIA_TYPE`. A `DELETE` reads
  no body, so its `Content-Type` is not checked. Multipart parsing is off,
  because the API takes no uploads and the parser runs before routing. With it
  on, a multipart `Content-Type` with no boundary was a 500 and an ERROR stack
  trace on every path, the public ones included. In JSON, a whole number sent as
  text, a status sent as a number and a departure time that is not an ISO-8601
  instant are each `400 MALFORMED_REQUEST`. None of them is converted into a value the
  client did not write.

- Error responses are `{code, message, timestamp}`, or
  `{code, fieldErrors, timestamp}` for a validation failure. They never carry
  stack traces, SQL or internal class names. The README lists the 22 codes.
  Every error the application writes is JSON, whatever the `Accept` header
  asks for. An API request
  that accepts only XML or YAML gets `406 REQUEST_REJECTED`, written as JSON.

- `X-Request-Id` is echoed on every response the application handles and
  appears in logs. A client's
  value is kept only if it matches `^[A-Za-z0-9._:-]{1,128}$`. Anything else
  is replaced with a generated UUID, because the value reaches a log line and
  a newline in it would forge a log entry. `idempotencyKey` takes the same
  character class, because it is logged on every replay and echoed in the 409
  message.

- The Lambda treats the `traceparent` SQS attribute the same way. It checks
  the value against the W3C shape before logging it, and drops a malformed one
  without failing the message. Values from the message body that it logs, the
  booking id and the exception message, go through
  `BookingEventHandler.printable`. Control, format and line-separator
  characters become `?`, and the value is capped at 1,000 characters. Jackson
  quotes rejected input in full, so without the cap a hostile body would
  reach CloudWatch whole.

- The service applies the same rule to what it logs from a rejected request.
  `GlobalExceptionHandler.printable` cleans Jackson's message, an unknown sort
  property and a constraint violation's database message before they reach the
  WARN line. A newline in a request body cannot start a forged log line on the
  plain-text console (`FlightControllerTest#rejectedValueCannotForgeALogLine`).

## Container and pod

- Non-root, with an explicit **numeric** UID (1001) in the image and in the
  pod's `runAsUser`. The kubelet checks `runAsNonRoot` against `runAsUser`
  when the pod sets it. Otherwise it checks the user in the image config and
  cannot resolve a username, so an image with `USER spring` would fail a pod
  that leaves `runAsUser` out.

- `readOnlyRootFilesystem: true`, with a 64 MiB in-memory `emptyDir` at `/tmp`
  for Tomcat's work directory and `hsperfdata`.

- `allowPrivilegeEscalation: false`, `capabilities: drop: ["ALL"]`,
  `seccompProfile: RuntimeDefault`.

- Multi-stage build. The runtime image has a JRE and one jar, and no compiler,
  Maven or source.

- `deploy/aws/cluster.yaml` puts the nodes in private subnets with no public
  IPs, so inbound traffic would arrive only through the load balancer. No
  cluster has been created.

- `deploy/aws/cluster.yaml` gives the node instance role no load balancer or
  autoscaler policy (`withAddonPolicies` sets only `cloudWatch`). A policy on
  the instance role is available to every pod on the node through the metadata
  service. The load balancer controller would get its own IRSA role.

- `up.sh` gives CI's role `AmazonEKSEditPolicy` **scoped to the `flight-ops`
  namespace**, so it could roll out the application and could not touch
  `kube-system`.

## AWS permissions

- The pods' IRSA role gets `SqsPublishPolicy` from `deploy/aws/foundation.yaml`:
  `sqs:SendMessage` on the `booking-events` queue and nothing else. The
  service sends with the queue URL from configuration, so it needs neither
  `GetQueueUrl` nor `GetQueueAttributes`.

- CI's role pins every ECR action to the one repository, apart from
  `ecr:GetAuthorizationToken`, which has no resource to scope to.
  `ecr:DescribeImages` serves the "Is this commit already in ECR?" step. That
  step fails the job on any error other than `ImageNotFoundException`, so a
  missing permission cannot pass for a missing image.

- The Lambda's role comes from SAM policy templates in `lambda/template.yaml`,
  and it is wider than the handler needs. `DynamoDBWritePolicy` grants `PutItem`,
  `UpdateItem` and `BatchWriteItem` on the table and its indexes, and the
  handler calls only `PutItem`. The SQS event adds
  `AWSLambdaSQSQueueExecutionRole`, whose SQS actions are on `Resource: '*'`.
  A hand-written role would narrow both.

## Supply chain

| | |
|---|---|
| Dependency updates | Dependabot, monthly, on both Maven modules, the Actions workflows and the Dockerfile base images |
| SBOM | CycloneDX, `target/bom.json`, on every build |
| Upper-bound dependency check | `maven-enforcer` `requireUpperBoundDeps`. A transitive downgrade fails the build |
| Coverage floor | JaCoCo. The build fails under 80% line or 50% branch coverage |
| Architecture rules | ArchUnit, 9 rules. A violation fails the build; it is not just reported |
| Vulnerability and secret scanning | Trivy scans the filesystem for vulnerabilities and committed secrets on every push or pull request to `main`, and fails on a fixable HIGH or CRITICAL. The `image` job scans the image it has just built, on the same triggers, and fails on a fixable CRITICAL. No image has ever been pushed. The deploy job, which is gated off and has never run, would repeat the image scan before a push. Only the filesystem scan uploads SARIF, and only on a push to `main`. A pull request from a fork has a read-only token, so the upload would fail on permissions and say nothing about the code. The image scans report in the job log, and ECR's own scan-on-push would cover the image in the registry |
| Static analysis | CodeQL `security-extended`, on every push or pull request to `main`, weekly, and by hand |
| Pinned actions | Every `uses:` is a full commit SHA with the version as a trailing comment, and Dependabot rewrites the comment along with the SHA. A tag is a mutable pointer in someone else's repository. Re-pointing `@v4` at a malicious commit needs no access to this repository, and that is what happened to `tj-actions/changed-files` in March 2025. The cost is a pull request for every patch release |

## Known limitations

1. **No rate limiting.** A single client can exhaust the connection pool. The
   right place for a limit is the ingress or a WAF, not application code.
   Neither has one, so nothing limits a client today, and that is a real gap.

2. **No TLS.** See [Transport](#transport).

3. **Kubernetes Secrets.** Secrets live in Kubernetes Secrets, not in Secrets
   Manager.

4. **No audit log.** The application log says which flight or booking was
   cancelled and when, but not who did it, and nothing keeps it.

5. **A laptop default profile.** The default profile is H2 in memory, `{noop}`
   passwords and seeded data. It starts, serves everything and stores nothing,
   and `./mvnw spring-boot:run` or a bare `java -jar` gets it. The image sets
   `SPRING_PROFILES_ACTIVE=prod`, so a container started with no profile fails
   closed: a bare `docker run` stops with `'url' must start with "jdbc"`. CI
   checks that on every push or pull request to `main`, in the `image` job's
   step "The image will not start without a database". The deploy job
   runs the same step before it pushes an image. That job is gated off, so its
   copy has never run. `compose.yaml` selects `postgres`, and the ConfigMap sets
   `prod`.

6. **A placeholder hash starts.** The startup self-check proves the encoder can
   read a value. It cannot tell a malformed value behind a known prefix from a
   wrong password, because `BCryptPasswordEncoder` returns false for both.
   `{bcrypt}REPLACE_ME`, the value in `k8s/secret.example.yaml`, passes both
   checks. The encoder logs `Encoded password does not look like BCrypt` at
   WARN, once at startup for each such value and again on every login. Every
   login as that user gets a 401, and the pod stays Ready.

7. **No penetration test.** The claims here come from reading the code and
   from the test suite. No independent party has tried to break it.
