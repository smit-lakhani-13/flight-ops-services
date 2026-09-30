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
#    the check into a silent pass. It is a skip on every other run: a local
#    run without the variable, and every pull request, this repository's
#    own included. GitHub runs one from a fork or Dependabot without
#    repository secrets, and the script does not tell them apart, so a
#    removed secret first fails on the next push or manual run.
#
#    A corrupt secret fails every run. grep reports a pattern it cannot
#    compile as an error and matches nothing, which the checks would read as a
#    pass, and a pattern that matches an empty line would flag every line.
#    The pattern itself is never printed.
#
# The history walk reads every commit message on every ref and each annotated
# tag's own message, which GitHub shows with the release. The home-directory
# and supplied-pattern checks read binary files (the console's screenshots) as
# text as well, and name only the file, since its bytes would garble the log.
# scripts/sweeps-selftest.sh plants each kind of finding in scratch
# repositories and checks that this script fails on it.
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
# word in a link's host, or next to a link, is still caught. It runs byte by
# byte (LC_ALL=C): in a UTF-8 locale, macOS's sed stops at the first byte that
# is not UTF-8, and every line after it would go unchecked.
without_url_paths() { LC_ALL=C sed -E 's#(https?://[^/[:space:]]*)[^[:space:]]*#\1#g'; }

# This file names the built-in patterns, so it is the one path left out.
SELF=':(exclude)scripts/sweeps.sh'

# tracked <text|binary> <pathspec>...: the tracked files git reads as text,
# or as binary, NUL-separated, so every file is in exactly one list. grep reads
# both as text (-a): without it, GNU grep reports a match in a file with a
# byte that is not UTF-8 only as "binary file matches" on standard error,
# which the checks do not read. For a binary file the checks print only its
# name. Every grep that takes these names ends its options with --, so a file
# whose name starts with '-' is read as a file.
#
# The home-directory checks, and the supplied-pattern check on binary files,
# match byte by byte (LC_ALL=C). In a UTF-8 locale, macOS's grep skips a line
# that is not UTF-8 in a file with no NUL byte, even with -a. The
# home-directory pattern is ASCII, and a binary file's bytes are not text in
# any locale. The supplied-pattern check on text files keeps the locale, so -i
# still folds the case of a letter outside ASCII; CI's GNU grep reads such a
# line either way.
tracked() {
    local kind=$1 entry
    shift
    git ls-files -z --eol -- "$@" | while IFS= read -r -d '' entry; do
        case $entry in i/-text*) [ "$kind" = binary ] ;; *) [ "$kind" = text ] ;; esac \
            && printf '%s\0' "${entry#*$'\t'}"
    done
}

# Every commit message on every ref, then each annotated tag's message. A
# lightweight tag has no message of its own, so it prints an empty line.
messages() {
    git log --all --format='%H %B'
    git for-each-ref refs/tags \
        --format='%(if:equals=tag)%(objecttype)%(then)%(refname) %(contents)%(end)'
}

echo 'Commit hygiene'
report 'no trailer lines in tracked files' \
    "$(git grep -n -i -E "$TRAILER" -- . "$SELF" 2>/dev/null || true)"
report 'no generated-with signatures in tracked files' \
    "$(git grep -n -E "$SIGNATURE" -- . "$SELF" 2>/dev/null || true)"
report 'no absolute home-directory paths in tracked files' \
    "$(tracked text . "$SELF" | LC_ALL=C xargs -0 grep -H -n -a -E -e "$HOME_PATH" -- 2>/dev/null \
        | without_url_paths | LC_ALL=C grep -a -E -e "$HOME_PATH" || true)"
report 'no absolute home-directory paths in tracked binary files' \
    "$(tracked binary . "$SELF" | LC_ALL=C xargs -0 grep -l -a -E -e "$HOME_PATH" -- 2>/dev/null || true)"

if [ "$TREE_ONLY" = 0 ]; then
    # Across all refs: a branch pushed once is public whether or not it merged.
    report 'no trailer lines in commit or tag messages' \
        "$(messages | grep -a -i -E "$TRAILER" || true)"
    report 'no generated-with signatures in commit or tag messages' \
        "$(messages | grep -a -E "$SIGNATURE" || true)"
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
    # Every grep takes the pattern after -e, so one that starts with '-' is
    # not read as an option. The pattern is tried in this locale and in C,
    # where the binary check reads it: a bracket expression can compile in
    # one and not the other.
    printf '\n' | grep -E -e "$SWEEP_PATTERNS" >/dev/null 2>&1
    in_locale=$?
    printf '\n' | LC_ALL=C grep -E -e "$SWEEP_PATTERNS" >/dev/null 2>&1
    in_c=$?
    if [ "$in_locale" -gt 1 ] || [ "$in_c" -gt 1 ]; then
        probe=2
    elif [ "$in_locale" -eq 0 ] || [ "$in_c" -eq 0 ]; then
        probe=0
    else
        probe=1
    fi
    case $probe in
        0)
            report 'SWEEP_PATTERNS is a usable pattern' \
                'it matches an empty line, so it would flag every line: look for an empty alternative, such as a trailing |' ;;
        1)
            report 'SWEEP_PATTERNS is a usable pattern' ''
            report 'no supplied pattern in tracked file paths' \
                "$(git ls-files | grep -a -i -E -e "$SWEEP_PATTERNS" || true)"
            report 'no supplied pattern in tracked files' \
                "$(tracked text . | xargs -0 grep -H -n -a -i -E -e "$SWEEP_PATTERNS" -- 2>/dev/null \
                    | without_url_paths | grep -a -i -E -e "$SWEEP_PATTERNS" || true)"
            report 'no supplied pattern in tracked binary files' \
                "$(tracked binary . | LC_ALL=C xargs -0 grep -l -a -i -E -e "$SWEEP_PATTERNS" -- 2>/dev/null || true)"
            if [ "$TREE_ONLY" = 0 ]; then
                report 'no supplied pattern in commit or tag messages' \
                    "$(messages | without_url_paths | grep -a -i -E -e "$SWEEP_PATTERNS" || true)"
            fi ;;
        *)
            report 'SWEEP_PATTERNS is a usable pattern' \
                'grep cannot compile it, in this locale or in C (an unbalanced parenthesis or bracket?): fix the SWEEP_PATTERNS secret' ;;
    esac
fi

echo
if [ "$failures" -eq 0 ]; then
    echo 'All sweeps pass.'
    exit 0
fi
printf '%d sweep(s) failed.\n' "$failures" >&2
exit 1
