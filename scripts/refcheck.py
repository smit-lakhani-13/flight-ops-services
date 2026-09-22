#!/usr/bin/env python3
"""Checks that every file path this repository's documentation names exists.

Prose rots differently from links. A link at least looks broken when you click
it; a sentence that says "see `src/main/java/com/smit/flightops/service/
OutboxPoller.java`" goes on reading perfectly after the class is renamed, and
the reader is the one who discovers it is gone. Documentation that cites code
is only worth more than documentation that waves at it if the citations are
enforced, so they are enforced here.

What counts as a citation: an inline code span whose contents look like a path
this repository could contain -- it has a directory separator or a known
source extension, and nothing but path characters. `SELECT ... FOR UPDATE` and
`kubectl set image` are not paths and are ignored; `k8s/hpa.yaml` and
`src/main/java/com/smit/flightops/entity/Flight.java` are.

The `path#symbol` form is checked one level deeper: the symbol must actually
appear in that file. That is what stops an ADR from citing a method that was
renamed in the commit it is describing.

Deliberately NOT a Java parser. `#symbol` is matched as a word in the file,
which accepts a mention in a comment as proof the symbol exists. The
alternative is a language-aware checker per file type -- and a docs gate that
needs maintaining is a docs gate somebody turns off.

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
# Named files that carry no extension, plus directories worth citing by name.
BARE_FILES = ('Dockerfile', 'mvnw', 'LICENSE')

# Paths the documentation names on purpose although they are not in the
# repository, each with the reason it is not. The list is short and it is
# meant to stay short: every entry is a place where prose describes a file
# somebody creates or will create, and anything longer than this is the
# checker being worked around rather than used.
DELIBERATELY_ABSENT = {
    # Written by the reader from k8s/secret.example.yaml. Committing the real
    # one would commit the passwords, which is the entire point of the split.
    'k8s/secret.yaml': 'created by the reader, never committed',
    # The shape a breaking change would take. It does not exist because no
    # breaking change has been made.
    'booking-created-v2.json': 'hypothetical, in the contract-change procedure',
    'contracts/booking-created-v2.json': 'hypothetical, in the contract-change procedure',
}


def tracked_files():
    out = subprocess.run(['git', 'ls-files', '-z'], capture_output=True, text=True, check=True)
    return set(p for p in out.stdout.split('\0') if p)


def looks_like_a_path(span):
    if not PATH_CHARS.match(span) or span.startswith('#'):
        return False
    # A leading slash makes it a URL path -- `/v3/api-docs.yaml` is an endpoint
    # this service serves, not a file it contains.
    if span.startswith('/'):
        return False
    path = span.split('#', 1)[0]
    if not path:
        return False
    if path in BARE_FILES or os.path.basename(path) in BARE_FILES:
        return True
    if not path.endswith(EXTENSIONS):
        return False
    # A bare filename with no directory is only a claim about this repository
    # if the repository actually has a file by that name somewhere; otherwise
    # it is prose ("application.yml" in a sentence about Spring in general).
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

                    if path in DELIBERATELY_ABSENT:
                        checked += 1
                        continue
                    # Build outputs are cited (an SBOM, a coverage report) and
                    # are never tracked. Their directory is the proof they are
                    # generated; checking they exist would mean checking that
                    # somebody had run a build, which is not this gate's job.
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
                        # `cluster.yaml` means the one in the root, and making
                        # every sentence write the full path would be worse
                        # prose for no more safety.
                        resolved = basenames[path][0]
                    elif '/' not in path and path in basenames:
                        checked += 1
                        continue
                    elif len(suffix_matches(path, tracked)) == 1:
                        # A package-relative citation -- `config/SecurityConfig.java`
                        # rather than the full src/main/java/com/smit/flightops/
                        # prefix. Accepted only when exactly one tracked file
                        # ends that way, so it cannot quietly point at the wrong
                        # one of two files with the same name.
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
