#!/usr/bin/env bash
#
# Live demo of flight-ops-service over real HTTP.
#
#   Terminal 1:  ./mvnw spring-boot:run
#   Terminal 2:  ./demo.sh                # pauses between acts so you can talk
#                ./demo.sh --fast         # no pauses
#
# Default profile is in-memory H2 seeded with 3 flights, so there is nothing
# to install and nothing to clean up. Restarting the app resets all state.
#
# The API requires credentials. The default profile ships two {noop} accounts
# so this script works with no setup (see application.yml); a deployment
# overrides both with bcrypt hashes. They are separate on purpose:
#   api / dev-secret  holds flights:read + flights:write   -> /api/v1/**
#   ops / dev-ops     holds ROLE_OPS                       -> /actuator/metrics
# Health is deliberately open to nobody in particular, because a Kubernetes
# probe has no credentials to present.

set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
API="$BASE/api/v1"
AUTH="${AUTH:--u api:dev-secret}"
OPS_AUTH="${OPS_AUTH:--u ops:dev-ops}"

# Both hold curl FLAGS -- "-u user:pass" -- which is two arguments, not one, so
# the value has to word-split. Most calls below go through run(), which evals a
# command string, and splitting there is the shell's normal behaviour. The
# handful of direct calls are the problem: unquoting $AUTH at each of them
# leaves a reader (and shellcheck) unable to tell deliberate splitting from a
# forgotten quote. Splitting once, here, into an array says it explicitly.
#
# The ${arr[@]+...} wrapper is not decoration. Under `set -u`, bash 3.2 -- what
# macOS ships, and what this script runs under -- treats "${arr[@]}" on an
# EMPTY array as an unbound variable and aborts. This form expands to nothing
# when the array is empty and to the elements otherwise.
read -ra auth_args <<< "$AUTH"
read -ra ops_args  <<< "$OPS_AUTH"
AUTH_ARGS=(${auth_args[@]+"${auth_args[@]}"})
OPS_ARGS=(${ops_args[@]+"${ops_args[@]}"})
FAST=0
[[ "${1:-}" == "--fast" ]] && FAST=1

bold=$'\033[1m'; dim=$'\033[2m'; grn=$'\033[32m'; red=$'\033[31m'; ylw=$'\033[33m'; off=$'\033[0m'

# The password inside $AUTH, so the echoed commands below can be redacted.
# Locally that is `dev-secret` and nobody cares. When deploy/aws/up.sh runs this
# against a deployed cluster, $AUTH carries a freshly generated production
# password -- and `run()` would otherwise print it on roughly fifteen lines,
# into a terminal whose scrollback outlives the demo. The commands still RUN
# with the real value; only what is printed is redacted.
api_secret=""; ops_secret=""
case "$AUTH"     in *:*) api_secret=${AUTH#*:} ;; esac
case "$OPS_AUTH" in *:*) ops_secret=${OPS_AUTH#*:} ;; esac

redact() {
  local s=$1
  [ -n "$api_secret" ] && s=${s//"$api_secret"/********}
  [ -n "$ops_secret" ] && s=${s//"$ops_secret"/********}
  printf '%s' "$s"
}

act()  { echo; echo "${bold}──────────────────────────────────────────────────────────────${off}"
         echo "${bold}$1${off}"; echo "${bold}──────────────────────────────────────────────────────────────${off}"; }
say()  { echo "${dim}$1${off}"; }
run()  { echo "${ylw}\$ $(redact "$1")${off}"; eval "$1"; echo; }
pause(){ [[ $FAST -eq 1 ]] && return; echo; read -rsp "${dim}[enter to continue]${off}" _; echo; }

jq_or_cat() { if command -v jq >/dev/null 2>&1; then jq .; else python3 -m json.tool; fi; }

# ── preflight ────────────────────────────────────────────────────────────────
if ! curl -fsS -o /dev/null "$BASE/actuator/health" 2>/dev/null; then
  echo "${red}The app is not responding at $BASE${off}"
  echo "Start it first, in another terminal:"
  echo "    cd $(dirname "$0") && ./mvnw spring-boot:run"
  exit 1
fi
echo "${grn}App is up at $BASE${off}"
# Deliberately unauthenticated, and it is the first thing the script proves:
# /actuator/health has to answer a caller that has no credentials, because the
# kubelet is exactly such a caller and cannot be given any.

# The credential check reads the STATUS CODE rather than curl's exit status.
# `curl -f` fails the same way on 401 and on 404, and those want opposite
# advice: one is "your password is wrong", the other is "this environment has
# no seed data". Telling an operator on a freshly deployed cluster that "the
# default profile expects api/dev-secret" sends them looking in the wrong place
# entirely.
probe=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH_ARGS[@]}" "$API/flights/UA123" 2>/dev/null || echo 000)
case "$probe" in
  401)
    echo "${red}Health is up, but the API did not accept these credentials (401).${off}"
    echo "The default profile expects api/dev-secret. Override with:"
    echo "    AUTH='-u someone:something' ./demo.sh"
    exit 1
    ;;
  403)
    echo "${red}The credentials are valid but lack flights:read (403).${off}"
    echo "This demo needs the api account, not ops."
    exit 1
    ;;
  000|5*)
    echo "${red}The API is not answering (got '${probe}') even though health passed.${off}"
    exit 1
    ;;
esac

# ── seed the flights the narration names ─────────────────────────────────────
# UA123 and UA456 are seeded on boot by DataSeeder -- on the default profile.
# The prod profile sets app.seed.enabled: false deliberately (DataSeeder's own
# javadoc explains the check-then-insert race that would crash-loop a pod), so
# against a real deployment these flights do not exist and every act that names
# one would 404. Creating them here makes the script environment-independent
# and costs two requests.
#
# 409 is success, not failure: the flight is already there, which is the normal
# case locally and on any second run. That is also what makes this script
# re-runnable against a persistent database, which it was not before.
ensure_flight() {  # ensure_flight <number> <origin> <dest> <seats>
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH_ARGS[@]}" \
    -X POST "$API/flights" -H 'Content-Type: application/json' \
    -d "{\"flightNumber\":\"$1\",\"origin\":\"$2\",\"destination\":\"$3\",\"totalSeats\":$4,\"departureTime\":\"2026-12-01T10:00:00Z\"}")
  case "$code" in
    201|409) return 0 ;;
    *) echo "${red}Could not ensure flight $1 exists (HTTP $code).${off}"; return 1 ;;
  esac
}
ensure_flight UA123 EWR LHR 180 || exit 1
ensure_flight UA456 ORD SFO 150 || exit 1

# ── per-run identifiers ──────────────────────────────────────────────────────
# Three of the acts MUTATE state: act 1 creates a flight, act 3 cancels it, and
# acts 4 and 5 sell seats off two more. Against in-memory H2 that is free —
# restarting the app resets everything. Against the deployed RDS instance, which
# is where up.sh runs this, nothing resets: a second run would get 409 on every
# create, then narrate "expect 49 seats left" at a flight that already has 40.
#
# So the mutated identifiers carry a four-digit run suffix and the read-only
# ones (UA123, UA456) do not. The narration still names the flights the README
# names, and the script is honest the second time somebody runs it.
#
# Four digits, because CreateFlightRequest caps flightNumber at @Size(max = 10)
# and "RACE1" plus four is nine.
RUN=$(date +%s); RUN=${RUN: -4}
NEW_FLIGHT="ua999$RUN"          # act 1 creates it, act 3 cancels it
RACE_A="RACE1$RUN"              # act 4, ten identical bodies on one key
RACE_B="RACE2$RUN"              # act 5, ten different bodies on one key

# ── Act 1 ────────────────────────────────────────────────────────────────────
act "ACT 1 — the basics: it is a real REST API"
say "Three flights are seeded at startup. Search is paged, not an unbounded list."
say "Note the -u: every /api/v1 call is authenticated. Health was not, a moment ago."
run "curl -s $AUTH '$API/flights' | jq_or_cat"
pause

say "Creating a flight. Note I send it lowercase — the service normalises it,"
say "and the Location header names the URL that actually resolves, not the one I typed."
run "curl -s -i $AUTH -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$NEW_FLIGHT\",\"origin\":\"ewr\",\"destination\":\"lhr\",\"totalSeats\":3,\"departureTime\":\"2026-12-01T10:00:00Z\"}' | head -4"
pause

# ── Act 2 ────────────────────────────────────────────────────────────────────
act "ACT 2 — idempotency: a retry is not an error"
say "POST a booking. 201, with a Location header."
POST_BOOKING="curl -s -i $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA123\",\"passengerName\":\"Smit Lakhani\",\"seats\":2,\"idempotencyKey\":\"demo-key-$RUN\"}'"
echo "${ylw}\$ $(redact "$POST_BOOKING") | head -4${off}"
HDRS=$(eval "$POST_BOOKING")
printf '%s\n' "$HDRS" | head -4
echo

# Read the Location value the server actually sent rather than assuming
# /bookings/1. This is the same discipline as the fix for bug 2, where the
# test asserted the header's text instead of following it — a demo that
# hardcodes the id would be committing the very mistake this repo documents.
LOC=$(printf '%s\n' "$HDRS" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
if [[ -z "$LOC" ]]; then
  echo "${red}No Location header on that 201 — which is itself the bug this act is about.${off}"
  exit 1
fi

say "Follow that header — the script reads the value the server sent rather than"
say "assuming /bookings/1. It resolves; this endpoint threw"
say "LazyInitializationException until I found and fixed it (bug 4)."
run "curl -s $AUTH '$BASE$LOC' | jq_or_cat"
pause

say "Now replay the EXACT same idempotency key. Still 201, same bookingId,"
say "and the seat count does not move. A retry after a timeout is safe."
run "curl -s $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA123\",\"passengerName\":\"Smit Lakhani\",\"seats\":2,\"idempotencyKey\":\"demo-key-$RUN\"}' | jq_or_cat"
run "curl -s $AUTH '$API/flights/UA123' | jq_or_cat"
pause

# ── Act 3 ────────────────────────────────────────────────────────────────────
act "ACT 3 — the errors are deliberate, not accidental"
say "Bean validation reports per FIELD, so a client can attach each message to an input."
run "curl -s $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"\",\"passengerName\":\"\",\"seats\":0,\"idempotencyKey\":\"\"}' | jq_or_cat"

say "Overselling is 409, not 400: the request was fine, the state of the resource refused."
run "curl -s $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$NEW_FLIGHT\",\"passengerName\":\"Greedy\",\"seats\":5,\"idempotencyKey\":\"oversell-$RUN\"}' | jq_or_cat"
pause

say "BUG 1: cancel a flight, then try to book it. Before the fix this returned 201"
say "and sold a seat on a flight that was not going anywhere."
run "curl -s $AUTH -o /dev/null -w 'DELETE -> %{http_code}\n' -X DELETE '$API/flights/$NEW_FLIGHT'"
run "curl -s $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$NEW_FLIGHT\",\"passengerName\":\"TooLate\",\"seats\":1,\"idempotencyKey\":\"cancelled-$RUN\"}' | jq_or_cat"
say "Note the code is FLIGHT_NOT_BOOKABLE, not INSUFFICIENT_SEATS — told 'not enough"
say "seats' a client retries with fewer, forever. Told 'CANCELLED' it stops."
pause

# ── Act 4 ────────────────────────────────────────────────────────────────────
act "ACT 4 — the finale: ten callers race on one idempotency key"
say "This is bug 3. Before the fix, roughly 4 callers got 201 and 6 got 409 —"
say "for what should have been one logical booking. Watch all ten get 201."
say "All ten send the IDENTICAL body: one request, retried, which is what a"
say "retry actually is. The next act sends ten different bodies on one key."
run "curl -s $AUTH -o /dev/null -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$RACE_A\",\"origin\":\"EWR\",\"destination\":\"SFO\",\"totalSeats\":50,\"departureTime\":\"2026-12-01T10:00:00Z\"}'"

echo "${ylw}\$ seq 1 10 | xargs -P 10 ... POST /bookings  (all with idempotencyKey=race-demo-1-$RUN)${off}"
TMP=$(mktemp -d)
seq 1 10 | xargs -P 10 -I{} curl -s "${AUTH_ARGS[@]}" -o "$TMP/{}.json" -w "%{http_code}\n" \
  -X POST "$API/bookings" -H 'Content-Type: application/json' \
  -d "{\"flightNumber\":\"$RACE_A\",\"passengerName\":\"Smit Lakhani\",\"seats\":1,\"idempotencyKey\":\"race-demo-1-$RUN\"}" \
  | sort | uniq -c | sed 's/^/   /'

echo
echo "   distinct bookingIds returned across all ten responses:"
cat "$TMP"/*.json | python3 -c "
import sys,json
ids=set()
for line in sys.stdin.read().replace('}{','}\n{').splitlines():
    try: ids.add(json.loads(line).get('bookingId'))
    except Exception: pass
print('     ', ids, '<- exactly one')"
rm -rf "$TMP"

echo
echo "   seats debited (50 total, expect 49 — one seat, not ten):"
curl -s "${AUTH_ARGS[@]}" "$API/flights/$RACE_A" | python3 -c "import sys,json;print('     availableSeats =',json.load(sys.stdin)['availableSeats'])"
echo "   booking rows on $RACE_A (expect exactly 1):"
# $.content, not the top level. The list endpoint returns a PagedModel
# envelope - {"content":[...],"page":{...}} - so len() on the parsed document
# would print 2 and look like two bookings. A demo that miscounts the headline
# number is worse than one that does not print it.
curl -s "${AUTH_ARGS[@]}" "$API/flights/$RACE_A" >/dev/null
curl -s "${AUTH_ARGS[@]}" "$API/bookings?flightNumber=$RACE_A" | python3 -c "
import sys,json
page=json.load(sys.stdin)
print('     rows =',len(page['content']),' (page.totalElements =',page['page']['totalElements'],')')"

echo
say "In the app log you can see WHICH path each caller took:"
say "  'Lost an idempotency-key race ... recovering the winner's booking'"
say "      = a genuine race loser, recovered in a fresh transaction (this is the fix)"
say "  'Idempotent replay of key ...'"
say "      = arrived after the winner committed, took the cheap read path"
say "The split varies run to run. That is what a real race looks like."
pause

# ── Act 5 ────────────────────────────────────────────────────────────────────
act "ACT 5 — the other half of the key: same key, DIFFERENT request"
say "Ten callers again, one key again — but this time ten different passengers."
say "That is not a retry, it is a key collision, and returning the first"
say "caller's booking to the other nine would hand nine people somebody else's"
say "reservation while telling all of them 201. One wins; nine get 409."
run "curl -s $AUTH -o /dev/null -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$RACE_B\",\"origin\":\"EWR\",\"destination\":\"SEA\",\"totalSeats\":50,\"departureTime\":\"2026-12-01T10:00:00Z\"}'"

echo "${ylw}\$ seq 1 10 | xargs -P 10 ... POST /bookings  (one key, ten different passengers)${off}"
seq 1 10 | xargs -P 10 -I{} curl -s "${AUTH_ARGS[@]}" -o /dev/null -w "%{http_code}\n" \
  -X POST "$API/bookings" -H 'Content-Type: application/json' \
  -d "{\"flightNumber\":\"$RACE_B\",\"passengerName\":\"Passenger {}\",\"seats\":1,\"idempotencyKey\":\"race-demo-2-$RUN\"}" \
  | sort | uniq -c | sed 's/^/   /'

echo
echo "   seats debited (50 total, expect 49 — the nine losers leave no trace):"
curl -s "${AUTH_ARGS[@]}" "$API/flights/$RACE_B" | python3 -c "import sys,json;print('     availableSeats =',json.load(sys.stdin)['availableSeats'])"
say "The 409 code is IDEMPOTENCY_KEY_REUSED. The service compares a SHA-256"
say "fingerprint of the normalised request against the one stored with the key,"
say "so 'the same request' means the same request and not just the same key."
pause

# ── Act 6 ────────────────────────────────────────────────────────────────────
act "ACT 6 — a flight status is a state machine, not a column"
say "SCHEDULED -> BOARDING is legal. Watch it take."
run "curl -s $AUTH -o /dev/null -w 'PATCH BOARDING -> %{http_code}\n' -X PATCH '$API/flights/UA456/status' \\
  -H 'Content-Type: application/json' -d '{\"status\":\"BOARDING\"}'"
run "curl -s $AUTH '$API/flights/UA456' | jq_or_cat"

say "ARRIVED without ever having DEPARTED is not. A flight cannot arrive"
say "somewhere it never left, and the entity refuses rather than the caller"
say "being trusted to ask sensible questions."
run "curl -s $AUTH -X PATCH '$API/flights/UA456/status' \\
  -H 'Content-Type: application/json' -d '{\"status\":\"ARRIVED\"}' | jq_or_cat"
pause

# ── Act 7 ────────────────────────────────────────────────────────────────────
act "ACT 7 — who is allowed to ask"
say "No credentials at all: 401, with the same {code,message,timestamp} body"
say "every other error uses. Spring Security's default here is an empty body."
run "curl -s -i '$API/flights/UA123' | head -1"
run "curl -s '$API/flights/UA123' | jq_or_cat"

say "The ops credential is valid — and still refused. 403, not 401: I know who"
say "you are, and the answer is still no. 401 would send a correct client into"
say "a credential-refresh loop that can never succeed."
run "curl -s -o /dev/null -w 'ops -> /api/v1/flights  %{http_code}\n' $OPS_AUTH '$API/flights/UA123'"
run "curl -s -o /dev/null -w 'api -> /actuator/metrics %{http_code}\n' $AUTH '$BASE/actuator/metrics'"
say "Two credentials, neither of which can do the other's job. A leaked"
say "scraper password does not book flights."
pause

# ── Act 8 ────────────────────────────────────────────────────────────────────
act "ACT 8 — the operational surface Kubernetes would use"
say "These three are the ONLY unauthenticated endpoints, and they have to be:"
say "the kubelet sends no credentials and there is nowhere to put any."
for p in health health/liveness health/readiness; do
  printf "   /actuator/%-18s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/$p")"
done
printf "   /actuator/prometheus       -> %s (no credentials)\n" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/prometheus")"
printf "   /actuator/prometheus       -> %s lines of metrics (as ops)\n" "$(curl -s "${OPS_ARGS[@]}" "$BASE/actuator/prometheus" | wc -l | tr -d ' ')"
say "Liveness and readiness are split because Kubernetes asks two different questions:"
say "'is this process wedged, restart it?' and 'can it take traffic right now?'"
say "The db indicator sits in readiness only — a database outage should take the"
say "pod out of the load balancer, not restart every replica in a loop."
echo
say "Public does not mean detailed: anonymous sees status, ops sees components."
run "curl -s '$BASE/actuator/health' | jq_or_cat"
run "curl -s $OPS_AUTH '$BASE/actuator/health' | jq_or_cat"

echo
echo "${grn}${bold}Demo complete.${off}"
echo "${dim}Restart the app to reset all state — it is in-memory H2.${off}"
