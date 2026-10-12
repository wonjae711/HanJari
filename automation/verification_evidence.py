"""HJ-001 source manifest and required Maven report policy."""
import hashlib
import xml.etree.ElementTree as ET

EXPECTED = {
    'surefire-reports': {'MovieControllerTest': 13, 'MovieIdParserTest': 16, 'MovieServiceTest': 2},
    'failsafe-reports': {'MovieApiIT': 4},
}

def source_manifest(repo):
    paths = list((repo / 'src').rglob('*')) + list((repo / '.mvn').rglob('*'))
    paths += [repo / name for name in ('pom.xml', 'mvnw', 'mvnw.cmd')]
    return {p.relative_to(repo).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths) if p.is_file()}

def check_reports(repo, since):
    summaries = []
    for folder, required in EXPECTED.items():
        found = set()
        for report in (repo / 'target' / folder).glob('TEST-*.xml'):
            if report.stat().st_mtime < since:
                continue
            root = ET.parse(report).getroot()
            name = root.get('name', '').split('.')[-1]
            counts = {k: int(root.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
            if root.tag != 'testsuite' or counts['tests'] <= 0 or any(counts[k] != 0 for k in ('failures', 'errors', 'skipped')):
                raise ValueError('Failed, skipped, empty, or invalid test suite: ' + report.name)
            if name in required:
                if counts['tests'] < required[name]:
                    raise ValueError('Required test coverage decreased: ' + name)
                found.add(name)
            summaries.append({'suite': name, **counts, 'report': report.relative_to(repo).as_posix(),
                              'sha256': hashlib.sha256(report.read_bytes()).hexdigest()})
        if found != set(required):
            raise ValueError('Missing or stale required suites: ' + ', '.join(sorted(set(required) - found)))
    return summaries
