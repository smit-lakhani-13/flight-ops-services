#!/usr/bin/env bash
# What this project has cost so far, and what it is on track to cost.
#
#   ./deploy/aws/cost-check.sh        # the last 7 days
#   ./deploy/aws/cost-check.sh 14     # the last 14 days
#
# Run it the morning after up.sh, and then daily. The forecast is the number
# that matters: a demo left running is not noticed by the daily figure, it is
# noticed by the invoice.
#
# Cost Explorer lags by 8-24 hours. Today's row is always incomplete and often
# missing entirely. That is the API, not this script.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/aws/lib.sh
. "$here/lib.sh"

DAYS="${1:-7}"
case "$DAYS" in ''|*[!0-9]*) die "usage: $0 [days]  (a whole number)" ;; esac

require_tool aws
ACCOUNT_ID=$(require_credentials)

# BSD date (macOS, where this repo is developed) and GNU date (Linux, CI)
# disagree on relative dates, and neither accepts the other's syntax.
if date -v-1d +%Y-%m-%d >/dev/null 2>&1; then
    ago() { date -u -v-"$1"d +%Y-%m-%d; }
    month_start() { date -u +%Y-%m-01; }
    next_month() { date -u -v+1m +%Y-%m-01; }
else
    ago() { date -u -d "$1 days ago" +%Y-%m-%d; }
    month_start() { date -u +%Y-%m-01; }
    next_month() { date -u -d "$(date -u +%Y-%m-01) +1 month" +%Y-%m-%d; }
fi
TODAY=$(date -u +%Y-%m-%d)
START=$(ago "$DAYS")

# Cost Explorer itself charges $0.01 per paginated request. Four calls a day is
# $1.20/year, which is worth knowing about and not worth worrying about.
step "Daily cost by service, $START to $TODAY (account $ACCOUNT_ID)"

# Formatting a day-by-service breakdown is more than awk wants to do, and
# --output table prints one row per service per day with no totals. Ten lines
# of Python is the smaller thing to read.
read -r -d '' FORMAT_DAYS <<'PYEOF' || true
import json, sys
data = json.load(sys.stdin)
if not data:
    print("  no data yet - Cost Explorer lags 8-24 hours behind the first resource")
    sys.exit()
for day in data:
    items = sorted(day["Items"], key=lambda i: -float(i["Cost"]))
    total = sum(float(i["Cost"]) for i in items)
    print()
    print(f'  {day["Date"]}   total ${total:7.2f}')
    for i in items:
        cost = float(i["Cost"])
        if cost < 0.005:
            continue
        print(f'      {i["Service"][:44]:<44} ${cost:7.2f}')
PYEOF

# shellcheck disable=SC2016  # the backticks are JMESPath literals; letting the
# shell see them would try to run `0` as a command.
aws ce get-cost-and-usage \
    --time-period "Start=$START,End=$TODAY" \
    --granularity DAILY \
    --metrics UnblendedCost \
    --group-by Type=DIMENSION,Key=SERVICE \
    --query 'ResultsByTime[].{Date: TimePeriod.Start, Items: Groups[?Metrics.UnblendedCost.Amount!=`0`].{Service: Keys[0], Cost: Metrics.UnblendedCost.Amount}}' \
    --output json 2>/dev/null | python3 -c "$FORMAT_DAYS"

step "This project only (tag Project=flight-ops)"
# Untagged spend is real spend. The cluster control plane, for instance, is not
# taggable by eksctl in every account, so this figure is a floor and the
# per-service list above is the truth.
aws ce get-cost-and-usage \
    --time-period "Start=$START,End=$TODAY" \
    --granularity DAILY \
    --metrics UnblendedCost \
    --filter '{"Tags":{"Key":"Project","Values":["flight-ops"]}}' \
    --query 'ResultsByTime[].{Date: TimePeriod.Start, Cost: Total.UnblendedCost.Amount}' \
    --output text 2>/dev/null | awk '{printf "  %s  $%.2f\n", $2, $1}' \
    || warn "no tagged data — cost allocation tags take up to 24h to activate after up.sh"

step "Forecast to the end of this month"
MONTH_START=$(month_start)
NEXT_MONTH=$(next_month)
mtd=$(aws ce get-cost-and-usage \
    --time-period "Start=$MONTH_START,End=$TODAY" \
    --granularity MONTHLY --metrics UnblendedCost \
    --query 'ResultsByTime[0].Total.UnblendedCost.Amount' --output text 2>/dev/null || echo 0)
printf '  month to date          $%.2f\n' "$mtd"

# The forecast needs a future window; on the 1st of the month there is none,
# and the call fails with a message about an invalid time period rather than
# returning zero.
if [ "$TODAY" != "$MONTH_START" ]; then
    forecast=$(aws ce get-cost-forecast \
        --time-period "Start=$TODAY,End=$NEXT_MONTH" \
        --granularity MONTHLY --metric UNBLENDED_COST \
        --query 'Total.Amount' --output text 2>/dev/null || echo "")
    if [ -n "$forecast" ] && [ "$forecast" != "None" ]; then
        printf '  forecast, rest of month $%.2f\n' "$forecast"
        printf '  %sprojected month total  $%.2f%s\n' "$C_BOLD" \
            "$(echo "$mtd $forecast" | awk '{print $1 + $2}')" "$C_RESET"
    else
        log "no forecast yet — AWS needs a few days of history"
    fi
fi

step "Budgets"
aws budgets describe-budgets --account-id "$ACCOUNT_ID" \
    --query "Budgets[?contains(BudgetName, 'flight-ops')].{Name: BudgetName, Limit: BudgetLimit.Amount, Spent: CalculatedSpend.ActualSpend.Amount, Forecast: CalculatedSpend.ForecastedSpend.Amount}" \
    --output table 2>/dev/null || warn "could not read budgets (needs budgets:ViewBudget)"

cat <<NOTE

  Expected, for the EKS shape, once everything is up: about \$7.72/day.

  Materially higher usually means one of three things:
    - a second NAT gateway (one per AZ if cluster.yaml's nat.gateway is not Single)
    - a load balancer left behind by a deleted Ingress — down.sh checks for this
    - an idle Elastic IP or unattached EBS volume from a failed teardown

  Stop all of it:  $here/down.sh

NOTE
