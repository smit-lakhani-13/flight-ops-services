#!/usr/bin/env bash
# Commit hygiene, checked over the working tree and the full commit history.
#
#   scripts/sweeps.sh              # working tree + full commit history
#   scripts/sweeps.sh --tree-only  # skip the history walk (faster)
#
# Run by CI on every trigger. Two kinds of check:
#
# 1. Built in. No trailer lines in commits or files (one author per commit),
#    no appended "Generated with [...]" signature, and no absolute home-directory
#    path, which is how a file from outside the worktree usually leaks in.
#
# 2. Supplied. Extra extended-regex patterns in SWEEP_PATTERNS (one pattern,
#    alternatives joined with |), checked case-insensitively against file
#    paths, file contents and commit messages. CI passes the repository secret
#    of the same name; locally, export it. Keeping those patterns out of this
#    file means the file never has to contain the strings it bans.
#
#    A missing secret is a failure on a push or a manual run, where CI always
#    has the repository's secrets: a deleted or renamed secret must not turn
#    the check into a silent pass. It is a skip only where no secret can exist:
#    a local run without the variable, and pull requests from forks or
#    Dependabot, which GitHub runs without repository secrets.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

TREE_ONLY=0
[ "${1:-}" = "--tree-only" ] && TREE_ONLY=1

failures=0
report() {  # report <label> <grep output>
    if [ -n "$2" ]; then
        printf '  FAIL  %s\n' "$1"
        printf '%s\n' "$2" | sed 's/^/          /'
        failures=$((failures + 1))
    else
        printf '  pass  %s\n' "$1"
    fi
}

# Anchored to line start: a trailer is a line, a mention is not.
TRAILER='^[[:space:]]*Co-authored-by:'
SIGNATURE='Generated with \['
HOME_PATH='/(Users|home)/[A-Za-z0-9._-]+/'

# The path of a URL can legitimately look like a banned word or a local path;
# a link into another project's documentation folder is the usual case. Only
# the path is exempt. It is cut out and the line is checked again, so a banned
# word in a link's host, or next to a link, is still caught.
without_url_paths() { sed -E 's#(https?://[^/[:space:]]*)[^[:space:]]*#\1#g'; }

# This file names the built-in patterns, so it is the one path left out.
SELF=':(exclude)scripts/sweeps.sh'

echo 'Commit hygiene'
report 'no trailer lines in tracked files' \
    "$(git grep -n -i -E "$TRAILER" -- . "$SELF" 2>/dev/null || true)"
report 'no generated-with signatures in tracked files' \
    "$(git grep -n -E "$SIGNATURE" -- . "$SELF" 2>/dev/null || true)"
report 'no absolute home-directory paths in tracked files' \
    "$(git grep -n -E "$HOME_PATH" -- . "$SELF" 2>/dev/null | without_url_paths \
        | grep -E "$HOME_PATH" || true)"

if [ "$TREE_ONLY" = 0 ]; then
    # Across all refs: a branch pushed once is public whether or not it merged.
    report 'no trailer lines in commit messages' \
        "$(git log --all --format='%H %B' | grep -i -E "$TRAILER" || true)"
    report 'no generated-with signatures in commit messages' \
        "$(git log --all --format='%H %B' | grep -E "$SIGNATURE" || true)"
fi

echo
echo 'Supplied patterns'
if [ -z "${SWEEP_PATTERNS:-}" ]; then
    case "${GITHUB_EVENT_NAME:-}" in
        push|workflow_dispatch)
            report 'SWEEP_PATTERNS is set' \
                "empty on a ${GITHUB_EVENT_NAME} run: add or restore the SWEEP_PATTERNS repository secret" ;;
        *)
            printf '  skip  SWEEP_PATTERNS is not set\n' ;;
    esac
else
    report 'no supplied pattern in tracked file paths' \
        "$(git ls-files | grep -i -E "$SWEEP_PATTERNS" || true)"
    report 'no supplied pattern in tracked files' \
        "$(git ls-files -z | xargs -0 grep -n -i -E "$SWEEP_PATTERNS" 2>/dev/null \
            | without_url_paths | grep -i -E "$SWEEP_PATTERNS" || true)"
    if [ "$TREE_ONLY" = 0 ]; then
        report 'no supplied pattern in commit messages' \
            "$(git log --all --format='%H %B' | without_url_paths \
                | grep -i -E "$SWEEP_PATTERNS" || true)"
    fi
fi

echo
if [ "$failures" -eq 0 ]; then
    echo 'All sweeps pass.'
    exit 0
fi
printf '%d sweep(s) failed.\n' "$failures" >&2
exit 1
