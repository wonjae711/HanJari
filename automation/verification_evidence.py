"""HJ-001 source manifest and required Maven report policy."""
import hashlib
import re
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

def task_policy(task):
    if task.get('task_id') == 'HJ-001' and task.get('kind') != 'planned_feature':
        return EXPECTED
    policy = task.get('verification_policy', {})
    required = policy.get('required_suites')
    if task.get('kind') != 'planned_feature' or policy.get('runner') != 'maven_verify' or not isinstance(required, dict) or set(required) != set(EXPECTED):
        raise ValueError('Task requires an approved Maven verification policy')
    baseline = task.get('project_context', {}).get('baseline', EXPECTED)
    for folder, suites in required.items():
        if not isinstance(suites, dict) or not suites:
            raise ValueError('Missing required test suites')
        for name, minimum in suites.items():
            if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:Test|IT)', name) or type(minimum) is not int or minimum < 1:
                raise ValueError('Invalid suite policy')
        if any(suites.get(name, 0) < minimum for name, minimum in baseline[folder].items()):
            raise ValueError('Pilot regression policy may not be reduced')
    return required

def check_reports(repo, since, expected=None):
    expected = EXPECTED if expected is None else expected
    summaries = []
    for folder, required in expected.items():
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
