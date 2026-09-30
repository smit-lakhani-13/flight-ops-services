#!/usr/bin/env bash
# Runs scripts/sweeps.sh against scratch repositories with planted findings,
# and checks that it fails on each kind it looks for: a trailer line and a
# generated-with signature, in files and in messages; a home-directory path,
# in text and binary files; and the supplied patterns in file paths, text
# files, binary files and commit and tag messages. It also checks that a
# pattern grep cannot use fails instead of passing.
#
#   scripts/sweeps-selftest.sh
#
# The planted strings are made up here, so none is a real supplied pattern.
# The trailer, the signature and the home-directory path are put together at
# run time, so this file never contains what the real sweep looks for. The
# value of SWEEP_PATTERNS in the caller's environment is never used or
# printed.
#
# CI's docs-check job runs this on the runner's GNU grep, before the real
# sweep. It needs bash (3.2 or later) and git.
set -uo pipefail

# Run from a git hook, git exports GIT_DIR and the like, and every git call
# below would then work on the caller's repository instead of a scratch one.
for var in $(git rev-parse --local-env-vars); do
    unset "$var"
done

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

FAILED=0
OUT="$tmp/out"

TRAILER_LINE=$(printf '%s-%s: Someone <someone@example.invalid>' Co-authored by)
SIGNATURE_LINE=$(printf '%s with [a tool](https://example.invalid)' Generated)
HOME_DIR=$(printf '/%s/%s' Users someone)

# git in a scratch repository, whatever the caller's config signs or hooks.
g() {
    git -C "$repo" -c user.name=selftest -c user.email=selftest@example.invalid \
        -c commit.gpgSign=false -c tag.gpgSign=false -c core.hooksPath=/dev/null "$@"
}

# new_repo <dir>: a repository with the script, a text file, a binary file
# and one commit, tagged with an annotated and a lightweight tag.
new_repo() {
    repo=$1
    mkdir -p "$repo/scripts" "$repo/doc"
    git -c init.defaultBranch=main init -q "$repo"
    cp "$here/sweeps.sh" "$repo/scripts/"
    printf 'Plain text.\n' > "$repo/doc/notes.md"
    printf '\211PNG\r\n\032\n\000\000plain pixels\000' > "$repo/doc/shot.png"
    g add -A && g commit -q -m 'First commit'
    g tag -a v0 -m 'First release'
    g tag light
}

# sweep <event> <patterns> [args...]: runs the copied script, leaving its
# output in $OUT and its exit status in $STATUS.
sweep() {
    local event=$1 patterns=$2; shift 2
    GITHUB_EVENT_NAME=$event SWEEP_PATTERNS=$patterns \
        bash "$repo/scripts/sweeps.sh" "$@" > "$OUT" 2>&1 < /dev/null
    STATUS=$?
}

pass() { printf '  ok    %s\n' "$1"; }
fail() {
    printf '  FAIL  %s: %s\n' "$1" "$2"
    sed 's/^/        | /' "$OUT" | tail -n 25
    FAILED=$((FAILED + 1))
}
expect_status() { [ "$STATUS" = "$2" ] || { fail "$1" "exit $STATUS, expected $2"; return 1; }; }
expect_out()    { grep -qF -- "$2" "$OUT" || { fail "$1" "no line containing '$2'"; return 1; }; }
reject_out()    { ! grep -qF -- "$2" "$OUT" || { fail "$1" "unexpected '$2'"; return 1; }; }

echo "sweeps.sh, clean repository"
new_repo "$tmp/clean"

name="no patterns on a pull request is a skip, and passes"
sweep pull_request ''
expect_status "$name" 0 && expect_out "$name" 'skip  SWEEP_PATTERNS is not set' && pass "$name"

name="no patterns on a push fails"
sweep push ''
expect_status "$name" 1 && expect_out "$name" 'FAIL  SWEEP_PATTERNS is set' && pass "$name"

name="a usable pattern that matches nothing passes every check, tags included"
sweep push 'xqzabsent'
expect_status "$name" 0 && expect_out "$name" 'pass  SWEEP_PATTERNS is a usable pattern' \
    && expect_out "$name" 'pass  no supplied pattern in tracked binary files' \
    && expect_out "$name" 'pass  no supplied pattern in commit or tag messages' && pass "$name"

name="a pattern grep cannot compile fails, runs no supplied check and is not printed"
sweep push 'xqz(open'
expect_status "$name" 1 && expect_out "$name" 'FAIL  SWEEP_PATTERNS is a usable pattern' \
    && reject_out "$name" 'no supplied pattern in' && reject_out "$name" 'xqz(open' && pass "$name"

# GNU grep reads an empty alternative as matching everything and BSD grep
# refuses it, so either way the sweep must fail. A grep that drops it matches
# only the other alternatives, so that case is a pattern like any other.
name="an empty alternative fails instead of flagging every line"
printf '\n' | grep -E -e 'xqz|' > /dev/null 2>&1
case $? in
    0|2)
        sweep push 'xqzabsent|'
        expect_status "$name" 1 && expect_out "$name" 'FAIL  SWEEP_PATTERNS is a usable pattern' \
            && reject_out "$name" 'no supplied pattern in' && reject_out "$name" 'xqzabsent' \
            && pass "$name" ;;
    *)
        printf '  skip  %s: this grep drops an empty alternative\n' "$name" ;;
esac

echo
echo "sweeps.sh, built-in checks"
new_repo "$tmp/hygiene"
printf 'Notes.\n%s\n' "$TRAILER_LINE" > "$repo/doc/trailer.md"
printf 'Written by hand. %s\n' "$SIGNATURE_LINE" > "$repo/doc/signature.md"
printf 'The manual is at https://example.com%s/manual.\nCopied from %s/notes.\n' \
    "$HOME_DIR" "$HOME_DIR" > "$repo/doc/home.md"
printf 'Caf\351, copied from %s/latin.\n' "$HOME_DIR" > "$repo/doc/latin1.md"
printf '\211PNG\r\n\032\n\000%s/xqzbytes\000' "$HOME_DIR" > "$repo/doc/home.png"
g add -A && g commit -q -m 'Add the notes'
g checkout -q -b side
g commit -q --allow-empty -m 'Only on a side branch' -m "$TRAILER_LINE"
g checkout -q main
g tag -a v1 -m "Second release. $SIGNATURE_LINE"
sweep pull_request ''

name="the built-in checks fail on what is planted"
expect_status "$name" 1 && pass "$name"

name="a trailer line in a tracked file fails and names the line"
expect_out "$name" 'FAIL  no trailer lines in tracked files' \
    && expect_out "$name" 'doc/trailer.md:2:' && pass "$name"

name="a generated-with signature in a tracked file fails and names the line"
expect_out "$name" 'FAIL  no generated-with signatures in tracked files' \
    && expect_out "$name" 'doc/signature.md:1:' && pass "$name"

name="a home-directory path in a text file fails, and one in a URL's path does not"
expect_out "$name" 'FAIL  no absolute home-directory paths in tracked files' \
    && expect_out "$name" 'doc/home.md:2:' && reject_out "$name" 'doc/home.md:1:' && pass "$name"

name="a home-directory path in a text file that is not UTF-8 fails"
expect_out "$name" 'doc/latin1.md:1:' && pass "$name"

name="a home-directory path inside a binary file fails and names only the file"
expect_out "$name" 'FAIL  no absolute home-directory paths in tracked binary files' \
    && expect_out "$name" 'doc/home.png' && reject_out "$name" 'xqzbytes' && pass "$name"

name="a trailer line in a commit message on another branch fails"
expect_out "$name" 'FAIL  no trailer lines in commit or tag messages' && pass "$name"

name="a generated-with signature in an annotated tag's message fails"
expect_out "$name" 'FAIL  no generated-with signatures in commit or tag messages' \
    && expect_out "$name" 'refs/tags/v1' && pass "$name"

echo
echo "sweeps.sh, supplied patterns"
new_repo "$tmp/planted"
printf 'A line that says xqzfile.\nA line that says -xqzdash.\n' > "$repo/doc/notes.md"
printf 'Caf\351, in Latin-1, says xqzlatin.\n' > "$repo/doc/latin1.txt"
printf '\211PNG\r\n\032\n\000\000xqzpixel, -xqzdash\000' > "$repo/doc/shot.png"
printf 'Named for what it holds.\n' > "$repo/doc/x-xqzdash.txt"
printf 'A file whose name starts with a dash says xqzlead.\n' > "$repo/-lead.md"
printf 'See https://example.com/docs/xqzurl/page.\nxqzurl, next to https://example.com/ and not in it.\n' \
    > "$repo/doc/links.md"
g add -A && g commit -q -m 'Second commit' -m 'It mentions -xqzdash.'
g tag -a v1 -m 'Second release, which says xqztag'
g tag -a a-latin -m "$(printf 'Caf\351, see https://example.com/x')"
g tag -a b-after -m 'Says xqzafter, in a later tag'

name="a pattern in a text file fails and names the line"
sweep push 'xqzfile'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in tracked files' \
    && expect_out "$name" 'doc/notes.md:1:' && pass "$name"

name="a pattern that starts with '-' is read as a pattern by every check"
sweep push '-xqzdash'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in tracked file paths' \
    && expect_out "$name" 'doc/x-xqzdash.txt' \
    && expect_out "$name" 'FAIL  no supplied pattern in tracked files' \
    && expect_out "$name" 'doc/notes.md:2:' \
    && expect_out "$name" 'FAIL  no supplied pattern in tracked binary files' \
    && expect_out "$name" 'FAIL  no supplied pattern in commit or tag messages' && pass "$name"

name="a file whose name starts with '-' is read as a file"
sweep push 'xqzlead'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in tracked files' \
    && expect_out "$name" '-lead.md:1:' && pass "$name"

name="a pattern in a URL's path passes, and one next to a URL fails"
sweep push 'xqzurl'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in tracked files' \
    && expect_out "$name" 'doc/links.md:2:' && reject_out "$name" 'doc/links.md:1:' && pass "$name"

# GNU grep reads a file with a byte that is not UTF-8 as binary; git reads it
# as text, so the line check must still see it. BSD grep in a UTF-8 locale
# skips such a line even with -a, so there the case is skipped; CI runs GNU.
name="a pattern in a text file that is not UTF-8 fails and names the line"
if printf 'Caf\351 xqz\n' | grep -a -e xqz > /dev/null 2>&1; then
    sweep push 'xqzlatin'
    expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in tracked files' \
        && expect_out "$name" 'doc/latin1.txt:1:' && pass "$name"
else
    printf '  skip  %s: this grep skips a line that is not UTF-8 in this locale\n' "$name"
fi

name="a pattern inside a binary file fails and names only the file"
sweep push 'xqzpixel'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in tracked binary files' \
    && expect_out "$name" 'doc/shot.png' && expect_out "$name" 'pass  no supplied pattern in tracked files' \
    && reject_out "$name" 'xqzpixel' && pass "$name"

name="a pattern in an annotated tag's message fails"
sweep push 'xqztag'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in commit or tag messages' \
    && expect_out "$name" 'refs/tags/v1' && pass "$name"

# In a UTF-8 locale, macOS's sed stops at the first byte that is not UTF-8,
# which would leave every later message unchecked.
name="a tag after one whose message is not UTF-8 is still read"
sweep push 'xqzafter'
expect_status "$name" 1 && expect_out "$name" 'FAIL  no supplied pattern in commit or tag messages' \
    && expect_out "$name" 'refs/tags/b-after' && pass "$name"

name="--tree-only leaves the tag messages out"
sweep push 'xqztag' --tree-only
expect_status "$name" 0 && reject_out "$name" 'commit or tag messages' && pass "$name"

echo
if [ "$FAILED" -gt 0 ]; then
    echo "sweeps selftest: $FAILED check(s) failed"
    exit 1
fi
echo "sweeps selftest: all checks passed"
