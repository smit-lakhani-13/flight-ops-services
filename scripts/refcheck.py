#!/usr/bin/env python3
"""Checks that every file path this repository's documentation names exists.

A broken link looks broken when clicked. A sentence citing
`src/main/java/com/smit/flightops/service/OutboxPoller.java` reads the same
after the class is renamed, so these citations are checked here.

A citation is an inline code span that looks like a path this repository could
contain: only path characters, ending in a known source extension or naming
one of BARE_FILES. `SELECT ... FOR UPDATE`, `kubectl set image` and a
directory such as `k8s/overlays/aws/` are ignored; `k8s/base/hpa.yaml` and
`src/main/java/com/smit/flightops/entity/Flight.java` are checked.

In the `path#symbol` form the symbol must also appear in that file, so an ADR
cannot cite a method the same commit renamed.

Not a Java parser. `#symbol` is matched as a word anywhere in the file, so a
mention in a comment counts. A language-aware checker per file type would need
upkeep of its own.

Usage: python3 scripts/refcheck.py [--quiet]
Exit status is 1 if any citation does not resolve.
"""

import os
import re
import subprocess
import sys

CODE_SPAN = re.compile(r'`([^`\n]+)`')
FENCE = re.compile(r'^\s*(```|~~~)')
PATH_CHARS = re.compile(r'^[A-Za-z0-9_./#-]+$')
EXTENSIONS = ('.java', '.yml', '.yaml', '.json', '.sql', '.md', '.sh', '.py',
              '.xml', '.txt', '.properties')
# Named files that carry no extension.
BARE_FILES = ('Dockerfile', 'mvnw', 'LICENSE')

# Paths the documentation names although they are not in the repository, each
# with its reason. Every entry is a file someone creates or will create; keep
# the list short.
EXPECTED_ABSENT = {
    # Written by the reader from k8s/secret.example.yaml. The real one holds
    # the passwords.
    'k8s/secret.yaml': 'created by the reader, never committed',
    # The shape a breaking change would take; none has been made.
    'booking-created-v2.json': 'hypothetical, in the contract-change procedure',
    'contracts/booking-created-v2.json': 'hypothetical, in the contract-change procedure',
}


def tracked_files():
    out = subprocess.run(['git', 'ls-files', '-z'], capture_output=True, text=True, check=True)
    return set(p for p in out.stdout.split('\0') if p)


def looks_like_a_path(span):
    if not PATH_CHARS.match(span) or span.startswith('#'):
        return False
    # A leading slash makes it a URL path: `/v3/api-docs.yaml` is an endpoint
    # this service serves, not a file.
    if span.startswith('/'):
        return False
    path = span.split('#', 1)[0]
    if not path:
        return False
    if path in BARE_FILES or os.path.basename(path) in BARE_FILES:
        return True
    if not path.endswith(EXTENSIONS):
        return False
    # A bare filename is a claim about this repository: main() accepts it when
    # a tracked file has that name and reports it otherwise, so a generic name
    # in prose, such as `bootstrap.yml`, fails the check.
    return True


def suffix_matches(path, tracked):
    needle = '/' + path.rstrip('/')
    return sorted(p for p in tracked if p.endswith(needle))


def main():
    quiet = '--quiet' in sys.argv
    tracked = tracked_files()
    basenames = {}
    for path in tracked:
        basenames.setdefault(os.path.basename(path), []).append(path)
    directories = set()
    for path in tracked:
        parent = os.path.dirname(path)
        while parent:
            directories.add(parent)
            parent = os.path.dirname(parent)

    docs = sorted(p for p in tracked if p.endswith('.md'))
    contents = {}
    problems = []
    checked = 0

    for doc in docs:
        in_fence, fence = False, None
        with open(doc, encoding='utf-8') as handle:
            for number, line in enumerate(handle, 1):
                marker = FENCE.match(line)
                if marker:
                    if not in_fence:
                        in_fence, fence = True, marker.group(1)
                    elif line.strip().startswith(fence):
                        in_fence, fence = False, None
                    continue
                if in_fence:
                    continue
                for span in CODE_SPAN.findall(line):
                    span = span.strip()
                    if not looks_like_a_path(span):
                        continue
                    path, _, symbol = span.partition('#')
                    if path.startswith('./'):
                        path = path[2:]
                    where = f'{doc}:{number}'

                    if path in EXPECTED_ABSENT:
                        checked += 1
                        continue
                    # Build outputs (an SBOM, a coverage report) are cited but
                    # never tracked, and whether someone ran a build is not
                    # this gate's question.
                    if path.startswith('target/') or '/target/' in path:
                        checked += 1
                        continue

                    if path in tracked:
                        resolved = path
                    elif path.rstrip('/') in directories:
                        checked += 1
                        continue
                    elif '/' not in path and len(basenames.get(path, [])) == 1:
                        # A bare filename is accepted when it is unambiguous:
                        # `cluster.yaml` means the one in the root.
                        resolved = basenames[path][0]
                    elif '/' not in path and path in basenames:
                        checked += 1
                        continue
                    elif len(suffix_matches(path, tracked)) == 1:
                        # A package-relative citation, such as
                        # `config/SecurityConfig.java`. Accepted only when one
                        # tracked file ends that way, so it cannot resolve to
                        # the wrong one of two files with the same name.
                        resolved = suffix_matches(path, tracked)[0]
                    else:
                        problems.append(f'{where}: no such tracked file: {span}')
                        continue

                    checked += 1
                    if not symbol:
                        continue
                    if resolved not in contents:
                        with open(resolved, encoding='utf-8', errors='replace') as source:
                            contents[resolved] = source.read()
                    if not re.search(r'\b' + re.escape(symbol) + r'\b', contents[resolved]):
                        problems.append(f'{where}: {resolved} does not contain {symbol}')

    for problem in problems:
        print(problem)
    if not quiet or problems:
        print(f'refcheck: {checked} path citation(s) in {len(docs)} file(s), '
              f'{len(problems)} unresolved')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
