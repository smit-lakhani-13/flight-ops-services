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

set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
API="$BASE/api/v1"
FAST=0
[[ "${1:-}" == "--fast" ]] && FAST=1

bold=$'\033[1m'; dim=$'\033[2m'; grn=$'\033[32m'; red=$'\033[31m'; ylw=$'\033[33m'; off=$'\033[0m'

act()  { echo; echo "${bold}──────────────────────────────────────────────────────────────${off}"
         echo "${bold}$1${off}"; echo "${bold}──────────────────────────────────────────────────────────────${off}"; }
say()  { echo "${dim}$1${off}"; }
run()  { echo "${ylw}\$ $1${off}"; eval "$1"; echo; }
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

# ── Act 1 ────────────────────────────────────────────────────────────────────
act "ACT 1 — the basics: it is a real REST API"
say "Three flights are seeded at startup. Search is paged, not an unbounded list."
run "curl -s '$API/flights' | jq_or_cat"
pause

say "Creating a flight. Note I send lowercase 'ua999' — the service normalises it,"
say "and the Location header names the URL that actually resolves, not the one I typed."
run "curl -s -i -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"ua999\",\"origin\":\"ewr\",\"destination\":\"lhr\",\"totalSeats\":3,\"departureTime\":\"2026-12-01T10:00:00Z\"}' | head -4"
pause

# ── Act 2 ────────────────────────────────────────────────────────────────────
act "ACT 2 — idempotency: a retry is not an error"
say "POST a booking. 201, with a Location header."
run "curl -s -i -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA123\",\"passengerName\":\"Smit Lakhani\",\"seats\":2,\"idempotencyKey\":\"demo-key-1\"}' | head -4"

say "Follow that Location header. It resolves — this endpoint threw"
say "LazyInitializationException until I found and fixed it (bug 4)."
run "curl -s '$API/bookings/1' | jq_or_cat"
pause

say "Now replay the EXACT same idempotency key. Still 201, same bookingId,"
say "and the seat count does not move. A retry after a timeout is safe."
run "curl -s -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA123\",\"passengerName\":\"Smit Lakhani\",\"seats\":2,\"idempotencyKey\":\"demo-key-1\"}' | jq_or_cat"
run "curl -s '$API/flights/UA123' | jq_or_cat"
pause

# ── Act 3 ────────────────────────────────────────────────────────────────────
act "ACT 3 — the errors are deliberate, not accidental"
say "Bean validation reports per FIELD, so a client can attach each message to an input."
run "curl -s -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"\",\"passengerName\":\"\",\"seats\":0,\"idempotencyKey\":\"\"}' | jq_or_cat"

say "Overselling is 409, not 400: the request was fine, the state of the resource refused."
run "curl -s -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA999\",\"passengerName\":\"Greedy\",\"seats\":5,\"idempotencyKey\":\"oversell-1\"}' | jq_or_cat"
pause

say "BUG 1: cancel a flight, then try to book it. Before the fix this returned 201"
say "and sold a seat on a flight that was not going anywhere."
run "curl -s -o /dev/null -w 'DELETE -> %{http_code}\n' -X DELETE '$API/flights/UA999'"
run "curl -s -X POST '$API/bookings' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"UA999\",\"passengerName\":\"TooLate\",\"seats\":1,\"idempotencyKey\":\"cancelled-1\"}' | jq_or_cat"
say "Note the code is FLIGHT_NOT_BOOKABLE, not INSUFFICIENT_SEATS — told 'not enough"
say "seats' a client retries with fewer, forever. Told 'CANCELLED' it stops."
pause

# ── Act 4 ────────────────────────────────────────────────────────────────────
act "ACT 4 — the finale: ten callers race on one idempotency key"
say "This is bug 3. Before the fix, roughly 4 callers got 201 and 6 got 409 —"
say "for what should have been one logical booking. Watch all ten get 201."
run "curl -s -o /dev/null -X POST '$API/flights' -H 'Content-Type: application/json' \\
  -d '{\"flightNumber\":\"RACE1\",\"origin\":\"EWR\",\"destination\":\"SFO\",\"totalSeats\":50,\"departureTime\":\"2026-12-01T10:00:00Z\"}'"

echo "${ylw}\$ seq 1 10 | xargs -P 10 ... POST /bookings  (all with idempotencyKey=race-demo-1)${off}"
TMP=$(mktemp -d)
seq 1 10 | xargs -P 10 -I{} curl -s -o "$TMP/{}.json" -w "%{http_code}\n" \
  -X POST "$API/bookings" -H 'Content-Type: application/json' \
  -d '{"flightNumber":"RACE1","passengerName":"Racer {}","seats":1,"idempotencyKey":"race-demo-1"}' \
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
curl -s "$API/flights/RACE1" | python3 -c "import sys,json;print('     availableSeats =',json.load(sys.stdin)['availableSeats'])"
echo "   booking rows on RACE1 (expect exactly 1):"
curl -s "$API/bookings?flightNumber=RACE1" | python3 -c "import sys,json;print('     rows =',len(json.load(sys.stdin)))"

echo
say "In the app log you can see WHICH path each caller took:"
say "  'Lost an idempotency-key race ... recovering the winner's booking'"
say "      = a genuine race loser, recovered in a fresh transaction (this is the fix)"
say "  'Idempotent replay of key ...'"
say "      = arrived after the winner committed, took the cheap read path"
say "The split varies run to run. That is what a real race looks like."
pause

# ── Act 5 ────────────────────────────────────────────────────────────────────
act "ACT 5 — the operational surface Kubernetes would use"
for p in health health/liveness health/readiness; do
  printf "   /actuator/%-18s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/$p")"
done
printf "   /actuator/prometheus       -> %s lines of metrics\n" "$(curl -s "$BASE/actuator/prometheus" | wc -l | tr -d ' ')"
say "Liveness and readiness are split because Kubernetes asks two different questions:"
say "'is this process wedged, restart it?' and 'can it take traffic right now?'"

echo
echo "${grn}${bold}Demo complete.${off}"
echo "${dim}Restart the app to reset all state — it is in-memory H2.${off}"
