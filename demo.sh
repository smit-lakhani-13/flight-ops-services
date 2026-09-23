#!/usr/bin/env bash
#
# Live demo of flight-ops-service over real HTTP.
#
#   Terminal 1:  ./mvnw spring-boot:run
#   Terminal 2:  ./demo.sh                # pauses between acts so you can talk
#                ./demo.sh --fast         # no pauses
#
# The default profile is in-memory H2 seeded with 3 flights, so there is
# nothing to install or clean up. Restarting the app resets all state.
#
# The API requires credentials. The default profile ships two {noop} accounts,
# so this works with no setup (see application.yml); a deployment overrides both
# with bcrypt hashes. Each account does one job:
#   api / dev-secret  holds flights:read + flights:write   -> /api/v1/**
#   ops / dev-ops     holds ROLE_OPS                       -> /actuator/metrics
# Health needs no credentials, because a Kubernetes probe has none to present.

set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
API="$BASE/api/v1"
AUTH="${AUTH:--u api:dev-secret}"
OPS_AUTH="${OPS_AUTH:--u ops:dev-ops}"

# Both hold curl flags ("-u user:pass"), which are two arguments, so the value
# has to word-split. The calls through run() split it as part of the eval. The
# direct calls use these arrays, split once here, so an unquoted $AUTH never
# looks like a forgotten quote.
#
# Under `set -u`, bash 3.2 (what macOS ships) treats "${arr[@]}" on an empty
# array as unbound and aborts. The ${arr[@]+...} form expands to nothing
# instead.
read -ra auth_args <<< "$AUTH"
read -ra ops_args  <<< "$OPS_AUTH"
AUTH_ARGS=(${auth_args[@]+"${auth_args[@]}"})
OPS_ARGS=(${ops_args[@]+"${ops_args[@]}"})
FAST=0
[[ "${1:-}" == "--fast" ]] && FAST=1

bold=$'\033[1m'; dim=$'\033[2m'; grn=$'\033[32m'; red=$'\033[31m'; ylw=$'\033[33m'; off=$'\033[0m'

# The passwords, so the echoed commands can be redacted. When up.sh runs this
# against the cluster, $AUTH carries a generated production password, and run()
# would print it on every echoed command, into scrollback that outlives the
# demo. The commands run with the real value; only the echo is redacted.
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
# The health check above sends no credentials, as the kubelet sends none.

# Reads the status code, not curl's exit status. `curl -f` fails the same way on
# 401 and 404, which need different advice: a wrong password, or an environment
# without the seed data.
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
# DataSeeder creates UA123 and UA456 on the default profile only. The prod
# profile sets app.seed.enabled: false (DataSeeder's javadoc has the race), so
# against a deployment every act naming them would 404. Two requests fix that.
#
# 409 counts as success: the flight is already there, as it is locally and on
# any second run against a persistent database.
#
# @Future on departureTime rejects a past date, so it is always 30 days ahead.
# BSD date (macOS) takes -v, GNU date takes -d.
DEPART=$(date -u -v+30d +%Y-%m-%dT10:00:00Z 2>/dev/null || date -u -d '+30 days' +%Y-%m-%dT10:00:00Z)
ensure_flight() {  # ensure_flight <number> <origin> <dest> <seats>
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH_ARGS[@]}" \
    -X POST "$API/flights" -H 'Content-Type: application/json' \
    -d "{\"flightNumber\":\"$1\",\"origin\":\"$2\",\"destination\":\"$3\",\"totalSeats\":$4,\"departureTime\":\"$DEPART\"}")
  case "$code" in
    201|409) return 0 ;;
    *) echo "${red}Could not ensure flight $1 exists (HTTP $code).${off}"; return 1 ;;
  esac
}
ensure_flight UA123 EWR LHR 180 || exit 1
ensure_flight UA456 ORD SFO 150 || exit 1

# ── per-run identifiers ──────────────────────────────────────────────────────
# Acts 1 and 3 create and cancel a flight, and acts 4 and 5 sell seats on two
# more. Locally a restart resets that. Against the RDS instance up.sh uses,
# nothing resets: a second run would get 409 on every create, then expect 49
# seats on a flight that already has 40.
#
# So the flights these acts change carry a four-digit run suffix, and UA123 and
# UA456 keep the names the README uses. Four digits, because CreateFlightRequest
# caps flightNumber at @Size(max = 10) and "RACE1" plus four is nine.
RUN=$(date +%s); RUN=${RUN: -4}
NEW_FLIGHT="ua999$RUN"          # act 1 creates it, act 3 cancels it
RACE_A="RACE1$RUN"              # act 4, ten identical bodies on one key
RACE_B="RACE2$RUN"              # act 5, ten different bodies on one key

# ── Act 1 ────────────────────────────────────────────────────────────────────
act "ACT 1: the REST API"
say "Locally, three flights are seeded at startup. Search is paged."
say "Note the -u: every /api/v1 call is authenticated. Health was not, a moment ago."
run "curl -s $AUTH '$API/flights' | jq_or_cat"
pause

say "Creating a flight. I send it lowercase; the service normalises it, and the"
say "Location header names the URL that resolves, not the one I typed."
run "curl -s -i $AUTH -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$NEW_FLIGHT\",\"origin\":\"ewr\",\"destination\":\"lhr\",\"totalSeats\":3,\"departureTime\":\"$DEPART\"}' | head -4"
pause

# ── Act 2 ────────────────────────────────────────────────────────────────────
act "ACT 2: idempotency"
say "POST a booking. 201, with a Location header."
POST_BOOKING="curl -s -i $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA123\",\"passengerName\":\"Smit Lakhani\",\"seats\":2,\"idempotencyKey\":\"demo-key-$RUN\"}'"
echo "${ylw}\$ $(redact "$POST_BOOKING") | head -4${off}"
HDRS=$(eval "$POST_BOOKING")
printf '%s\n' "$HDRS" | head -4
echo

# The server's Location, not an assumed /bookings/1. A missing header stops the
# demo here; one that does not resolve prints its error body when the act
# follows it below.
LOC=$(printf '%s\n' "$HDRS" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
if [[ -z "$LOC" ]]; then
  echo "${red}No Location header on that 201, which is the bug this act is about.${off}"
  exit 1
fi

say "Follow that header. The script reads the value the server sent instead of"
say "assuming /bookings/1. It resolves; this endpoint threw"
say "LazyInitializationException until I found and fixed it (bug 4)."
run "curl -s $AUTH '$BASE$LOC' | jq_or_cat"
pause

say "Now replay the same idempotency key. Still 201, same bookingId,"
say "and the seat count does not move. A retry after a timeout is safe."
run "curl -s $AUTH -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA123\",\"passengerName\":\"Smit Lakhani\",\"seats\":2,\"idempotencyKey\":\"demo-key-$RUN\"}' | jq_or_cat"
run "curl -s $AUTH '$API/flights/UA123' | jq_or_cat"
pause

# ── Act 3 ────────────────────────────────────────────────────────────────────
act "ACT 3: error responses"
say "Bean validation reports per field, so a client can attach each message to an input."
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
say "The code is FLIGHT_NOT_BOOKABLE, not INSUFFICIENT_SEATS. Told 'not enough"
say "seats', a client retries with fewer, for ever. Told 'CANCELLED', it stops."
pause

# ── Act 4 ────────────────────────────────────────────────────────────────────
act "ACT 4: ten callers race on one idempotency key"
say "This is bug 3. Before the fix, roughly 4 callers got 201 and 6 got 409"
say "for what should have been one logical booking. Watch all ten get 201."
say "All ten send the same body: one request, retried. The next act sends"
say "ten different bodies on one key."
run "curl -s $AUTH -o /dev/null -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$RACE_A\",\"origin\":\"EWR\",\"destination\":\"SFO\",\"totalSeats\":50,\"departureTime\":\"$DEPART\"}'"

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
print('     ', ids, '<- one')"
rm -rf "$TMP"

echo
echo "   seats debited (50 total, expect 49: one seat, not ten):"
curl -s "${AUTH_ARGS[@]}" "$API/flights/$RACE_A" | python3 -c "import sys,json;print('     availableSeats =',json.load(sys.stdin)['availableSeats'])"
echo "   booking rows on $RACE_A (expect 1):"
# $.content, not the top level. The list endpoint returns a PagedModel envelope,
# {"content":[...],"page":{...}}, so len() on the document would print 2.
curl -s "${AUTH_ARGS[@]}" "$API/bookings?flightNumber=$RACE_A" | python3 -c "
import sys,json
page=json.load(sys.stdin)
print('     rows =',len(page['content']),' (page.totalElements =',page['page']['totalElements'],')')"

echo
say "In the app log you can see which path each caller took:"
say "  'Lost an idempotency-key race on ...; recovered the winner's booking ...'"
say "      = a race loser, recovered in a fresh transaction (this is the fix)"
say "  'Idempotent replay of key ...'"
say "      = arrived after the winner committed, took the cheap read path"
say "The split varies from run to run."
pause

# ── Act 5 ────────────────────────────────────────────────────────────────────
act "ACT 5: one key, different requests"
say "Ten callers on one key again, but this time ten different passengers."
say "That is a key collision, not a retry. Returning the first caller's booking"
say "to the other nine would hand nine people someone else's reservation with a"
say "201. One wins; nine get 409."
run "curl -s $AUTH -o /dev/null -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"$RACE_B\",\"origin\":\"EWR\",\"destination\":\"SEA\",\"totalSeats\":50,\"departureTime\":\"$DEPART\"}'"

echo "${ylw}\$ seq 1 10 | xargs -P 10 ... POST /bookings  (one key, ten different passengers)${off}"
seq 1 10 | xargs -P 10 -I{} curl -s "${AUTH_ARGS[@]}" -o /dev/null -w "%{http_code}\n" \
  -X POST "$API/bookings" -H 'Content-Type: application/json' \
  -d "{\"flightNumber\":\"$RACE_B\",\"passengerName\":\"Passenger {}\",\"seats\":1,\"idempotencyKey\":\"race-demo-2-$RUN\"}" \
  | sort | uniq -c | sed 's/^/   /'

echo
echo "   seats debited (50 total, expect 49: the nine losers leave no trace):"
curl -s "${AUTH_ARGS[@]}" "$API/flights/$RACE_B" | python3 -c "import sys,json;print('     availableSeats =',json.load(sys.stdin)['availableSeats'])"
say "The 409 code is IDEMPOTENCY_KEY_REUSED. The service compares a SHA-256"
say "fingerprint of the normalised request against the one stored with the key,"
say "so 'the same request' means the same body as well as the same key."
pause

# ── Act 6 ────────────────────────────────────────────────────────────────────
act "ACT 6: flight status transitions"
say "SCHEDULED -> BOARDING is legal. Watch it take."
run "curl -s $AUTH -o /dev/null -w 'PATCH BOARDING -> %{http_code}\n' -X PATCH '$API/flights/UA456/status' \\
  -H 'Content-Type: application/json' -d '{\"status\":\"BOARDING\"}'"
run "curl -s $AUTH '$API/flights/UA456' | jq_or_cat"

say "ARRIVED without DEPARTED is not. A flight cannot arrive somewhere it never"
say "left, and the entity itself refuses the transition."
run "curl -s $AUTH -X PATCH '$API/flights/UA456/status' \\
  -H 'Content-Type: application/json' -d '{\"status\":\"ARRIVED\"}' | jq_or_cat"
pause

# ── Act 7 ────────────────────────────────────────────────────────────────────
act "ACT 7: authentication and roles"
say "No credentials at all: 401, with the same {code,message,timestamp} body"
say "every other error uses. Spring Security's default here is an empty body."
run "curl -s -i '$API/flights/UA123' | head -1"
run "curl -s '$API/flights/UA123' | jq_or_cat"

say "The ops credential is valid and still refused: 403, not 401. The caller is"
say "known and not allowed. A 401 would send a correct client into a"
say "credential-refresh loop that can never succeed."
run "curl -s -o /dev/null -w 'ops -> /api/v1/flights  %{http_code}\n' $OPS_AUTH '$API/flights/UA123'"
run "curl -s -o /dev/null -w 'api -> /actuator/metrics %{http_code}\n' $AUTH '$BASE/actuator/metrics'"
say "Two credentials, neither of which can do the other's job. A leaked"
say "scraper password does not book flights."
pause

# ── Act 8 ────────────────────────────────────────────────────────────────────
act "ACT 8: the endpoints Kubernetes uses"
say "These three health endpoints need no credentials, and they have to be:"
say "the kubelet sends none. The only other anonymous paths are the OpenAPI"
say "document and Swagger UI."
for p in health health/liveness health/readiness; do
  printf "   /actuator/%-18s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/$p")"
done
printf "   /actuator/prometheus       -> %s (no credentials)\n" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/prometheus")"
printf "   /actuator/prometheus       -> %s lines of metrics (as ops)\n" "$(curl -s "${OPS_ARGS[@]}" "$BASE/actuator/prometheus" | wc -l | tr -d ' ')"
say "Liveness and readiness are split because Kubernetes asks two different questions:"
say "'is this process wedged, restart it?' and 'can it take traffic right now?'"
say "The db indicator sits in readiness only. A database outage should take the"
say "pod out of the load balancer, not restart every replica in a loop."
echo
say "Anonymous and the api user see status only. Ops also sees the components,"
say "in every profile."
run "curl -s '$BASE/actuator/health' | jq_or_cat"
run "curl -s $OPS_AUTH '$BASE/actuator/health' | jq_or_cat"

echo
echo "${grn}${bold}Demo complete.${off}"
case "$BASE" in
  http://localhost:*|http://localhost|http://127.0.0.1:*|http://127.0.0.1)
    echo "${dim}Restart the app to reset all state; the default profile is in-memory H2.${off}" ;;
esac
