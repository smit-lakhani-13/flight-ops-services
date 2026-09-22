# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: **Security → Report a
vulnerability** on this repository. It opens a private advisory that only the
maintainer can see. A public issue tells everyone at once.

If that button is missing, the setting is off. Then open a public issue with
one sentence and no detail: "I have a security finding, please open a private
channel." Do not put the finding in it. A reporter who must choose between full
disclosure and silence usually stays silent, and the fallback keeps a third
option open.

Please include what you did, what happened and what you expected. A proof of
concept helps. A working exploit is not required.

Supported: `main`. There is no support branch for older tags.

## What this is

A demonstration service. It holds seeded data and has never processed a real
booking. At the time of writing it has run only on a laptop and in CI. I wrote
the AWS path and checked it with linters, and the deploy job is gated off.
[DEPLOYMENT.md](DEPLOYMENT.md) opens with an `Executed on:` line that will
carry the date if that changes.

Some decisions below suit a demo and would be wrong for a system with real
users. Each one is marked where it comes up.

## Authentication and authorisation

There are two ways in and one rule set. HTTP Basic is always on. JWT bearer
tokens are accepted when `spring.security.oauth2.resourceserver.jwt.issuer-uri`
is set, and the startup log says which mode is active. Both feed the same
`authorizeHttpRequests` block, so a rule can be wrong in only one place. See
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
| `/actuator/health`, `/liveness`, `/readiness` | anyone. The kubelet has no credentials |
| `/actuator/**` (everything else) | `ROLE_OPS` |
| `/v3/api-docs/**`, `/swagger-ui/**` | anyone, for `GET` and `HEAD`. See below |
| `/error` | anyone. It is the container's own forward target and is not an API route |
| `/api/v1/**` | `flights:read` for `GET` and `HEAD`; `flights:write` for `POST`, `PATCH` and `DELETE` |
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
Valid credentials without the authority get `403 FORBIDDEN`. Collapsing the two
sends a correct client into a credential refresh loop over a permissions
problem.

`security/JsonAuthenticationEntryPoint` and `security/JsonAccessDeniedHandler`
write both. The `@RestControllerAdvice` never sees them, because Spring
Security rejects the request before the `DispatcherServlet` runs. Without those
two writers the client would get an HTML error page where every other error is
JSON.

### The OpenAPI document is public and the API is not

`/v3/api-docs` and Swagger UI are reachable without credentials. I chose this
and recorded it in [adr/0012](adr/0012-openapi-public-read.md). The document
describes paths and schemas, and every operation still requires
authentication. Needing credentials to read about an API you cannot call helps
no one. Set `SWAGGER_UI_ENABLED=false` to serve the JSON without the UI.

What that concedes: an unauthenticated visitor learns the shape of the API. A
service on a private network could reasonably withhold that. For one on a
public URL, anyone can learn the same by reading this repository.

## Sessions and CSRF

`SessionCreationPolicy.STATELESS`, with CSRF disabled. Both follow from there
being no cookie. CSRF protects a browser that attaches credentials on its own,
and an `Authorization` header is not attached automatically. Leaving CSRF on
with no session produces a 403 on every POST and a day of debugging. See
[adr/0006](adr/0006-stateless-sessions-no-csrf.md).

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
  `{id}` prefix, as in `{bcrypt}$2y$10$…`. `ApiSecurityProperties` checks this
  when the properties are bound, so a missing or unprefixed value stops the pod
  with an error naming the property. Without the check, Spring's delegating
  encoder would throw `IllegalArgumentException` on the first login, and a pod
  that reports itself healthy would answer 500. The check only requires some
  `{id}`. An id the encoder does not know, such as `{BCRYPT}`, passes it: the
  pod goes Ready and answers every login as that user with a 500
  `INTERNAL_ERROR`. The id is case-sensitive, so write `{bcrypt}`.

- **A rejected password is printed.** Spring Boot's startup failure report
  shows the rejected value. A plaintext password set without the prefix
  therefore ends up in the pod log. Rotate it.

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
  the Secrets Store CSI driver. It is not installed here. I count that as a
  gap, not as a trade-off I made.

- **No AWS access keys anywhere.** In the cluster the pods use IRSA: a
  projected, short-lived token exchanged with STS. CI uses GitHub's OIDC
  provider and short-lived STS credentials. The trust policy pins both the
  audience and the `sub` claim to `repo:owner/repo:ref:refs/heads/main`. A
  wildcard there would let any repository on GitHub assume the role.

## Transport

Nothing is deployed, so nothing is on the internet today. This section
describes what `k8s/components/ingress` would create the day someone applies
it. A transport decision is easier to review before it is taken.

That Ingress listens on **port 80 with no TLS**. Basic credentials would cross
the internet base64-encoded, and anyone who captures them can decode them.

That is acceptable for a short-lived demo with generated throwaway passwords
and no real data, and for nothing else. I did not use a self-signed
certificate, because it trains people to click through the warning that exists
to stop them. The HTTPS recipe (ACM, DNS validation, three Ingress annotations
and `server.forward-headers-strategy`) is in
[DEPLOYMENT.md §8](DEPLOYMENT.md#8-http-and-what-https-would-take). The work is
written down. What is missing is a domain.

## Data exposure

- `BookingDto` does **not** carry `idempotencyKey`. It used to. An idempotency
  key is a client's private token, and leaking it on a list endpoint would let
  any reader replay another client's booking.

- `passengerName` **is** returned on the list endpoint. I made that scope
  decision because this is an internal operations API, and the operator is
  entitled to see who is on the flight. The field is in the OpenAPI schema for
  the response, so no one has to guess.

- Error responses are `{code, message, timestamp}`. They never carry stack
  traces, SQL or internal class names. The README lists the 21 codes.

- `X-Request-Id` is echoed on every response and appears in logs. A client's
  value is kept only if it matches `^[A-Za-z0-9._:-]{1,128}$`. Anything else
  is replaced with a generated UUID, because the value reaches a log line and
  a newline in it would forge a log entry.

- The Lambda treats the `traceparent` SQS attribute the same way. It checks
  the value against the W3C shape before logging it, and drops a malformed one
  without failing the message.

## Container and pod

- Non-root, with an explicit **numeric** UID (1001). The kubelet enforces
  `runAsNonRoot` by reading the UID from the image config and cannot resolve a
  username, so `USER spring` would fail the pod at start.

- `readOnlyRootFilesystem: true`, with a 64 MiB in-memory `emptyDir` at `/tmp`
  for Tomcat's work directory and `hsperfdata`.

- `allowPrivilegeEscalation: false`, `capabilities: drop: ["ALL"]`,
  `seccompProfile: RuntimeDefault`.

- Multi-stage build. The runtime image has a JRE and one jar, and no compiler,
  Maven or source.

- Nodes are in private subnets with no public IPs. Inbound traffic arrives only
  through the load balancer.

- The node instance role does **not** carry the load balancer or autoscaler
  policies. A policy on the instance role is available to every pod on the
  node through the metadata service. The load balancer controller gets its own
  IRSA role.

- CI's cluster access is `AmazonEKSEditPolicy` **scoped to the `flight-ops`
  namespace**. It can roll out the application and cannot touch `kube-system`.

## Supply chain

| | |
|---|---|
| Dependency updates | Dependabot, monthly, on both Maven modules, the Actions workflows and the Dockerfile base images |
| SBOM | CycloneDX, `target/bom.json`, on every build |
| Upper-bound dependency check | `maven-enforcer` `requireUpperBoundDeps`. A transitive downgrade fails the build |
| Coverage floor | JaCoCo. The build fails under 80% line or 50% branch coverage |
| Architecture rules | ArchUnit, 9 rules. A violation fails the build; it is not just reported |
| Vulnerability and secret scanning | Trivy scans the filesystem on every run, and the image before it is pushed. Both fail the build on a fixable CRITICAL (the filesystem scan on HIGH too). Only the filesystem scan uploads SARIF, and only on a push. A pull request from a fork has a read-only token, so the upload would fail on permissions and say nothing about the code. The image scan reports in the job log, and ECR's own scan-on-push covers the image in the registry |
| Static analysis | CodeQL `security-extended`, on every push and pull request, and weekly |
| Pinned actions | Every `uses:` is a full commit SHA. A tag is a mutable pointer in someone else's repository |

## Known limitations

1. **No rate limiting.** A single client can exhaust the connection pool. The
   right place for a limit is the ingress or a WAF, not application code.
   Neither has one, so nothing limits a client today, and that is a real gap.

2. **No TLS.** See [Transport](#transport).

3. **Kubernetes Secrets.** Secrets live in Kubernetes Secrets, not in Secrets
   Manager.

4. **No audit log.** Who cancelled which flight is in the application log and
   nowhere durable.

5. **A laptop default profile.** The default profile is H2 in memory, `{noop}`
   passwords and seeded data. A container started with no
   `SPRING_PROFILES_ACTIVE` gets it: it starts, serves everything and stores
   nothing. `compose.yaml` and the ConfigMap both pin the profile for this
   reason.

6. **No penetration test.** The claims here come from reading the code and
   from the test suite. No independent party has tried to break it.
