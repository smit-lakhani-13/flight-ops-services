# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: **Security → Report a
vulnerability** on this repository. That opens a private advisory visible only
to the maintainer, which is the right channel — a public issue tells everyone
at once.

Please include what you did, what happened, and what you expected. A proof of
concept helps; a working exploit is not required.

Supported: `main`. There is no support branch for older tags.

## What this is

A demonstration service. It runs on a personal AWS account for days at a time,
holds seeded data, and has never processed a real booking. Several decisions
below are correct for that and would be wrong for a system with real users;
each one says so rather than being quietly presented as a best practice.

## Authentication and authorisation

Two mechanisms, **one rule set**. HTTP Basic is always on; JWT bearer tokens
are accepted when `spring.security.oauth2.resourceserver.jwt.issuer-uri` is
set, and the startup log says which mode is active. Both funnel into the same
`authorizeHttpRequests` block, so there is exactly one place where a rule can
be wrong — see [adr/0005](adr/0005-one-rule-set-for-basic-and-jwt.md).

| Path | Who |
|---|---|
| `/actuator/health`, `/liveness`, `/readiness` | anyone. The kubelet has no credentials |
| `/actuator/**` (everything else) | `ROLE_OPS` |
| `/v3/api-docs/**`, `/swagger-ui/**` | anyone — see below |
| `/api/v1/**` | `flights:read` / `flights:write` authorities |
| everything else | denied |

**401 and 403 are different answers.** No credentials, or credentials that do
not verify, is `401 UNAUTHENTICATED`. Valid credentials without the authority
is `403 FORBIDDEN`. Collapsing them sends a correct client into a credential
refresh loop over a permissions problem.

Both are written by `security/JsonAuthenticationEntryPoint` and
`security/JsonAccessDeniedHandler` rather than by the `@RestControllerAdvice`,
because Spring Security rejects the request before the `DispatcherServlet`
runs — so the advice never sees them, and without those two writers the client
would get an HTML error page where every other error is JSON.

### The OpenAPI document is public and the API is not

`/v3/api-docs` and Swagger UI are reachable without credentials. This is a
decision, recorded in [adr/0012](adr/0012-openapi-public-read.md), not an
oversight: the document describes paths and schemas, every operation still
requires authentication, and the alternative — credentials to read the
description of an API you cannot call — helps nobody. Set
`SWAGGER_UI_ENABLED=false` to serve the JSON without the UI.

What that does concede: an unauthenticated visitor learns the shape of the API.
For a service on a private network that is a reasonable thing to withhold. For
one on a public URL, anyone can learn the same by reading this repository.

## Sessions and CSRF

`SessionCreationPolicy.STATELESS`, CSRF disabled. Both follow from there being
no cookie: CSRF protects a browser that attaches credentials automatically, and
an `Authorization` header is not attached automatically. Leaving CSRF on with
no session produces 403s on every POST and a day of debugging.
[adr/0006](adr/0006-stateless-sessions-no-csrf.md).

If a browser-facing UI with cookie sessions is ever added, both decisions
reverse, and the ADR is where to start.

## Secrets

- **No credentials in the repository.** The default profile ships `{noop}`
  passwords for localhost. `application.yml` has no default for `DB_PASSWORD`
  at all, so a missing value fails startup with the property name rather than
  silently trying something.
- **`API_PASSWORD` and `OPS_PASSWORD` must carry an `{id}` prefix**
  (`{bcrypt}$2y$10$…`). `ApiSecurityProperties` validates this at startup.
  Without the guard, a bare hash would be compared as plaintext — Spring's
  delegating encoder in fact raises `IllegalArgumentException`, so the
  practical effect is a pod that fails rather than one that quietly accepts
  the hash itself as the password.
- **Generated, not chosen.** `deploy/aws/up.sh` generates the database and API
  passwords with `openssl rand`, hashes them with `htpasswd -bnBC 10`, writes
  them into a Kubernetes Secret, and prints them once. They never touch disk
  and never reach GitHub.
- **A Kubernetes Secret is base64, not encryption.** `k8s/secret.example.yaml`
  says so in its header. The production answer is AWS Secrets Manager through
  the Secrets Store CSI driver; it is not installed here, and that is a real
  gap rather than a considered trade.
- **No AWS access keys anywhere.** In the cluster the pods use IRSA — a
  projected, short-lived token exchanged with STS. In CI, GitHub's OIDC
  provider and short-lived STS credentials. The trust policy pins both the
  audience and the `sub` claim to `repo:owner/repo:ref:refs/heads/main`; a
  wildcard there lets any repository on GitHub assume the role.

## Transport

The deployed Ingress listens on **port 80 with no TLS**, so Basic credentials
cross the internet base64-encoded, which is encoding and not encryption.

That is acceptable for a short-lived demonstration with generated throwaway
passwords and no real data, and for nothing else. The reason it is not a
self-signed certificate is that a self-signed certificate trains people to
click through the warning that exists to stop them. The full HTTPS recipe —
ACM, DNS validation, three Ingress annotations, and
`server.forward-headers-strategy` — is in
[DEPLOYMENT.md §8](DEPLOYMENT.md#8-http-and-what-https-would-take). What is
missing is a domain, not the work.

## Data exposure

- `BookingDto` does **not** carry `idempotencyKey`. It used to. An idempotency
  key is a client's private token: leaking it on a list endpoint lets any
  reader of the list replay another client's booking.
- `passengerName` **is** returned on the list endpoint. That is a deliberate
  scope decision — the API is an internal operations API where the operator is
  entitled to see who is on the flight — and it is stated in the OpenAPI
  description so nobody has to guess.
- Error responses are `{code, message, timestamp}` and never carry stack
  traces, SQL, or internal class names. The 20 codes are enumerated in the
  README.
- `X-Request-Id` is echoed on every response and appears in logs. It is
  accepted from the client only if it matches `^[A-Za-z0-9._:-]{1,128}$`;
  anything else is replaced with a generated UUID, because the value reaches a
  log line and a newline in it forges a log entry.
- The Lambda applies the same reasoning to the `traceparent` SQS attribute: it
  is validated against the W3C shape before it is logged, and a malformed one
  is dropped rather than failing the message.

## Container and pod

- Non-root with an explicit **numeric** UID (1001). The kubelet enforces
  `runAsNonRoot` by reading the UID from the image config and cannot resolve a
  username, so `USER spring` would fail the pod at start.
- `readOnlyRootFilesystem: true`, with a 64 MiB in-memory `emptyDir` at `/tmp`
  because the JVM needs it for Tomcat's work directory and `hsperfdata`.
- `allowPrivilegeEscalation: false`, `capabilities: drop: ["ALL"]`,
  `seccompProfile: RuntimeDefault`.
- Multi-stage build: the runtime image has a JRE, one jar, and no compiler, no
  Maven and no source.
- Nodes are in private subnets with no public IPs. Inbound traffic arrives only
  through the load balancer.
- The node instance role does **not** carry the load balancer or autoscaler
  policies, because a policy on the instance role is available to every pod on
  the node through the metadata service. The load balancer controller gets its
  own IRSA role.
- CI's cluster access is `AmazonEKSEditPolicy` **scoped to the `flight-ops`
  namespace**. It can roll out the application and cannot touch `kube-system`.

## Supply chain

| | |
|---|---|
| Dependency updates | Dependabot, monthly, on both Maven modules, the Actions workflows and the Dockerfile base images |
| SBOM | CycloneDX, `target/bom.json`, on every build |
| Upper-bound dependency check | `maven-enforcer` `requireUpperBoundDeps` — a transitive downgrade fails the build |
| Coverage floor | JaCoCo, build fails under 80% line / 50% branch |
| Architecture rules | ArchUnit, 9 rules, failing the build not a report |
| Vulnerability and secret scanning | Trivy filesystem scan on every run, and the image before it is pushed; findings filed in the Security tab |
| Static analysis | CodeQL `security-extended`, on every push and pull request and weekly |
| Pinned actions | every `uses:` is a full commit SHA, not a tag — a tag is a mutable pointer in somebody else's repository |

## Known limitations

Stated because a security document that lists only what was done is marketing.

1. **No rate limiting.** A single client can exhaust the connection pool. The
   right place is the ingress or a WAF, not application code — but it is absent
   rather than delegated, and that is a real gap.
2. **No TLS on the deployed URL.** Above.
3. **Secrets are Kubernetes Secrets**, not Secrets Manager.
4. **No audit log.** Who cancelled which flight is in the application log and
   nowhere durable.
5. **The default profile is a laptop profile.** H2 in memory, `{noop}`
   passwords, seeded data. Running the container with no
   `SPRING_PROFILES_ACTIVE` gets you that — it starts, serves everything, and
   stores nothing. `compose.yaml` and the ConfigMap both pin the profile
   explicitly for exactly this reason.
6. **Nothing has been penetration tested.** The claims here are about what the
   code does, established by reading it and by the test suite. No independent
   party has tried to break it.
