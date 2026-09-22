#!/usr/bin/env python3
"""Checks every link inside the repository's own Markdown.

A dead link in a README is a small thing that says a large one: that the
document was written once and never read again. The two failure modes are
different and both silent.

* A **relative file link** breaks when a file is renamed. Nothing warns you --
  GitHub renders it as a link, and it 404s only for the person who clicks it,
  which is never the author.
* An **anchor** breaks when a heading is reworded, because GitHub derives the
  anchor from the heading text. `## Still open` becomes `#still-open`; rename
  it to `## Open` and every table of contents entry pointing at it silently
  stops jumping anywhere.

So both are checked here, against GitHub's actual slug algorithm rather than
an approximation of it: lowercase, drop everything that is not a letter,
digit, space, hyphen or underscore, then spaces to hyphens, and a `-1`, `-2`
suffix for repeated headings in one file.

External `http(s)` links are deliberately NOT fetched. A checker that needs
the network is a checker that fails in CI for reasons that have nothing to do
with the commit, and the first red build nobody believes is the last one
anybody reads.

Usage: python3 scripts/linkcheck.py [--quiet]
Exit status is 1 if anything is broken, which is what makes it a CI gate.
"""

import os
import re
import subprocess
import sys
import unicodedata

# [text](target) -- target stops at the first whitespace or closing paren, so a
# title like [x](y "z") is handled. Skips image links written as ![alt](src)
# only in the sense that they are checked identically, which is correct: a
# missing image is a broken link too.
LINK = re.compile(r'\[(?:[^\]]*)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)')
HEADING = re.compile(r'^(#{1,6})\s+(.*?)\s*#*\s*$')
FENCE = re.compile(r'^\s*(```|~~~)')


def tracked_files():
    out = subprocess.run(['git', 'ls-files', '-z'], capture_output=True, text=True, check=True)
    return set(p for p in out.stdout.split('\0') if p)


def slug(text):
    """GitHub's heading -> anchor transformation."""
    # Inline markup is stripped before slugging: `code`, **bold**, [links](x)
    # and the arrow characters this repository's headings use.
    text = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', text)
    text = text.replace('`', '').replace('*', '').replace('_', '')
    text = unicodedata.normalize('NFKC', text)
    kept = []
    for ch in text.lower():
        if ch.isalnum() or ch in ' -_':
            kept.append(ch)
        # Everything else -- punctuation, emoji, en dashes -- is dropped, not
        # replaced with a hyphen. That difference is the one people get wrong.
    return ''.join(kept).strip().replace(' ', '-')


def anchors(path):
    """Every anchor a Markdown file offers, in GitHub's numbering."""
    found, counts, in_fence, fence = [], {}, False, None
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            marker = FENCE.match(line)
            if marker:
                if not in_fence:
                    in_fence, fence = True, marker.group(1)
                elif line.strip().startswith(fence):
                    in_fence, fence = False, None
                continue
            if in_fence:
                continue
            heading = HEADING.match(line)
            if not heading:
                continue
            base = slug(heading.group(2))
            if not base:
                continue
            seen = counts.get(base, 0)
            counts[base] = seen + 1
            found.append(base if seen == 0 else f'{base}-{seen}')
    return set(found)


def main():
    quiet = '--quiet' in sys.argv
    tracked = tracked_files()
    docs = sorted(p for p in tracked if p.endswith('.md'))
    anchor_cache = {}
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
                for target in LINK.findall(line):
                    if target.startswith(('http://', 'https://', 'mailto:', 'tel:')):
                        continue
                    checked += 1
                    where = f'{doc}:{number}'
                    path, _, fragment = target.partition('#')

                    if path:
                        resolved = os.path.normpath(os.path.join(os.path.dirname(doc), path))
                        if resolved.startswith('..'):
                            problems.append(f'{where}: link escapes the repository: {target}')
                            continue
                        if resolved not in tracked and not os.path.isdir(resolved):
                            problems.append(f'{where}: no such tracked file: {target}')
                            continue
                    else:
                        resolved = doc

                    if fragment and resolved.endswith('.md'):
                        if resolved not in anchor_cache:
                            anchor_cache[resolved] = anchors(resolved)
                        if fragment.lower() not in anchor_cache[resolved]:
                            problems.append(
                                f'{where}: no heading in {resolved} produces #{fragment}')

    for problem in problems:
        print(problem)
    if not quiet or problems:
        print(f'linkcheck: {checked} link(s) in {len(docs)} file(s), {len(problems)} broken')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
