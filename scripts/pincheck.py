#!/usr/bin/env python3
"""Checks the version pins that must follow Spring Boot, or each other.

`pom.xml` overrides two versions Boot manages, and `lambda/pom.xml`, which has
no parent, copies four versions the service runs. Dependabot never proposes a
change to a property that only Boot's parent reads. The enforcer's
`requireUpperBoundDeps` fails a pin that Boot's own version has passed only
where something in the tree asks for Boot's version. Boot's Tomcat starters
do. For Jackson 2 only Jackson 3 does, for the annotations at its own minor
(2.21 for Jackson 3.1), so a Jackson 2 that passes the pin within its minor
line, as 2.22.3 would, fails nothing. Nor does a Boot that reaches the Tomcat
pin exactly, and the pin stays. And the two modules are separate builds, so a
lone `/lambda` pull request can split Jackson 2 or the AWS SDK with every
check green. This fails instead:

- Tomcat: once Boot manages the pinned version or a later one, the pin has
  done its job; drop `<tomcat.version>`, as the comment beside it says.
- Jackson 2: Boot's own version must not pass the pin, or the pin would hold
  the service back.
- `lambda/pom.xml`: the same Jackson 2 and AWS SDK as `pom.xml`, and the
  JUnit and Testcontainers that Boot gives the service.

Boot's versions come from the `spring-boot-dependencies` POM in the local
Maven repository, so run it after `./mvnw` has resolved the parent. CI runs
it after "Build and test". It reads no network.

Usage: python3 scripts/pincheck.py [--selftest]
Exit status is 1 if a pin is off or Boot's BOM is not in the local Maven
repository, or if a self-test case gets other findings than it expects.
"""

import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
ROOT = Path(__file__).resolve().parent.parent
BOM = 'org/springframework/boot/spring-boot-dependencies/{0}/spring-boot-dependencies-{0}.pom'


def properties(path):
    props = ET.parse(path).getroot().find('m:properties', NS)
    if props is None:
        return {}
    return {el.tag.split('}')[1]: (el.text or '').strip() for el in props}


def boot_version(path):
    return ET.parse(path).getroot().findtext('m:parent/m:version', namespaces=NS).strip()


def order(version):
    """11.0.100 sorts after 11.0.26: compared as numbers, not as text."""
    return tuple(int(part) for part in re.findall(r'\d+', version))


def problems(root, repo):
    service = properties(root / 'pom.xml')
    lam = properties(root / 'lambda' / 'pom.xml')
    boot = boot_version(root / 'pom.xml')
    bom = repo / BOM.format(boot)
    if not bom.is_file():
        return [f'{bom} is missing: run ./mvnw first, so Maven resolves Boot {boot}']
    managed = properties(bom)
    found = []
    # A pin that is gone is fine: the service then runs Boot's version.
    tomcat, pinned = managed['tomcat.version'], service.get('tomcat.version')
    if pinned and order(tomcat) >= order(pinned):
        found.append(f'Boot {boot} manages Tomcat {tomcat}, at or past the pin {pinned}: '
                     'drop <tomcat.version> from pom.xml')
    jackson, pinned = managed['jackson-2-bom.version'], service.get('jackson-2-bom.version')
    if pinned and order(jackson) > order(pinned):
        found.append(f'Boot {boot} manages Jackson 2 at {jackson}, past the pin {pinned}: '
                     'raise <jackson-2-bom.version> in pom.xml and <jackson.version> in lambda/pom.xml together')
    for key, what, want, source in (
            ('jackson.version', 'Jackson 2', pinned or jackson,
             '<jackson-2-bom.version> in pom.xml' if pinned else f'the Jackson 2 Boot {boot} gives the service'),
            ('aws.sdk.version', 'the AWS SDK', service.get('aws.sdk.version'), '<aws.sdk.version> in pom.xml'),
            ('junit.version', 'JUnit', managed['junit-jupiter.version'], f'the JUnit Boot {boot} gives the service'),
            ('testcontainers.version', 'Testcontainers', managed['testcontainers.version'],
             f'the Testcontainers Boot {boot} gives the service')):
        if lam.get(key) != want:
            found.append(f'lambda/pom.xml pins {what} at {lam.get(key)}, but {source} is {want}: '
                         'move both in one pull request')
    return found


POM = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><version>{boot}</version></parent>'
       '<properties>{props}</properties></project>')
GOOD = {
    'managed': {'tomcat.version': '11.0.24', 'jackson-2-bom.version': '2.21.5',
                'junit-jupiter.version': '6.0.3', 'testcontainers.version': '2.0.5'},
    'service': {'tomcat.version': '11.0.26', 'jackson-2-bom.version': '2.22.2', 'aws.sdk.version': '2.55.6'},
    'lambda': {'jackson.version': '2.22.2', 'aws.sdk.version': '2.55.6', 'junit.version': '6.0.3',
               'testcontainers.version': '2.0.5'},
}
# (POM, property, value, a phrase the one finding must contain; None for no finding).
# A value of None removes the property.
CASES = [
    (None, None, None, None),
    ('service', 'tomcat.version', None, None),
    ('managed', 'tomcat.version', '11.0.25', None),
    ('managed', 'tomcat.version', '11.0.26', 'Tomcat'),
    ('managed', 'tomcat.version', '11.0.100', 'Tomcat'),
    ('managed', 'tomcat.version', '12.0.1', 'Tomcat'),
    ('managed', 'jackson-2-bom.version', '2.22.2', None),
    ('managed', 'jackson-2-bom.version', '2.22.3', 'Jackson 2 at'),
    ('managed', 'jackson-2-bom.version', '2.23.0', 'Jackson 2 at'),
    ('managed', 'jackson-2-bom.version', '2.22.10', 'Jackson 2 at'),
    ('lambda', 'jackson.version', '2.22.3', 'pins Jackson 2'),
    ('service', 'jackson-2-bom.version', None, 'the Jackson 2 Boot 4.1.1 gives the service is 2.21.5'),
    ('lambda', 'jackson.version', None, 'pins Jackson 2 at None'),
    ('service', 'aws.sdk.version', '2.55.7', 'pins the AWS SDK'),
    ('lambda', 'aws.sdk.version', '2.55.7', 'pins the AWS SDK'),
    ('managed', 'junit-jupiter.version', '6.0.4', 'pins JUnit'),
    ('managed', 'testcontainers.version', '2.0.6', 'pins Testcontainers'),
    ('lambda', 'junit.version', '6.0.4', 'pins JUnit'),
    ('lambda', 'testcontainers.version', '2.0.6', 'pins Testcontainers'),
    ('service', 'aws.sdk.version', None, 'pins the AWS SDK at 2.55.6'),
    ('lambda', 'aws.sdk.version', None, 'pins the AWS SDK at None'),
    ('lambda', 'junit.version', None, 'pins JUnit at None'),
    ('lambda', 'testcontainers.version', None, 'pins Testcontainers at None'),
]


def write(root, repo, versions, boot='4.1.1'):
    def tags(props):
        return ''.join(f'<{k}>{v}</{k}>' for k, v in props.items())
    (root / 'lambda').mkdir(parents=True, exist_ok=True)
    (root / 'pom.xml').write_text(POM.format(boot=boot, props=tags(versions['service'])))
    (root / 'lambda' / 'pom.xml').write_text(POM.format(boot='', props=tags(versions['lambda'])))
    bom = repo / BOM.format(boot)
    bom.parent.mkdir(parents=True, exist_ok=True)
    bom.write_text(POM.format(boot='', props=tags(versions['managed'])))


def selftest():
    failures = []
    for pom, key, value, phrase in CASES:
        with tempfile.TemporaryDirectory() as tmp:
            versions = {name: dict(props) for name, props in GOOD.items()}
            if pom and value is None:
                del versions[pom][key]
            elif pom:
                versions[pom][key] = value
            write(Path(tmp) / 'tree', Path(tmp) / 'repo', versions)
            found = problems(Path(tmp) / 'tree', Path(tmp) / 'repo')
            label = f'{pom} {key}={value}' if pom else 'the good versions'
            if phrase is None and found:
                failures.append(f'{label}: expected no finding, got {found}')
            if phrase is not None and (len(found) != 1 or phrase not in found[0]):
                failures.append(f'{label}: expected one finding naming {phrase!r}, got {found}')
    with tempfile.TemporaryDirectory() as tmp:
        write(Path(tmp) / 'tree', Path(tmp) / 'repo', GOOD)
        (Path(tmp) / 'tree' / 'pom.xml').write_text(POM.format(boot='9.9.9', props=''))
        found = problems(Path(tmp) / 'tree', Path(tmp) / 'repo')
        if len(found) != 1 or 'is missing' not in found[0]:
            failures.append(f'a Boot whose BOM is not in the repository: got {found}')
    for failure in failures:
        print(f'FAIL {failure}')
    print(f'pincheck self-test: {len(CASES) + 1 - len(failures)} of {len(CASES) + 1} cases right')
    return not failures


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    repo = Path(os.environ.get('MAVEN_REPO_LOCAL', Path.home() / '.m2' / 'repository'))
    found = problems(ROOT, repo)
    prefix = '::error::' if os.environ.get('GITHUB_ACTIONS') == 'true' else ''
    for finding in found:
        print(prefix + finding)
    if not found:
        print(f"pincheck: every pin holds against Boot {boot_version(ROOT / 'pom.xml')}")
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main())
