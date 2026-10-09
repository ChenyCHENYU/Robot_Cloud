#!/usr/bin/env python3
"""Record actual XML counters and built artifact SHA; zero suites is a failure."""
import hashlib
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
reports = sorted(ROOT.glob('**/target/surefire-reports/TEST-*.xml')) + sorted(ROOT.glob('**/target/failsafe-reports/TEST-*.xml'))
counts = {'suites': len(reports), 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for path in reports:
    suite = ET.parse(path).getroot()
    for key in ('tests', 'failures', 'errors', 'skipped'):
        counts[key] += int(suite.attrib.get(key, 0))
if not counts['tests'] or counts['failures'] or counts['errors'] or counts['skipped']:
    raise SystemExit(f'verification XML counters are not acceptable: {counts}')
artifacts = []
for path in sorted(ROOT.glob('**/target/robot-*.jar')):
    artifacts.append({'path': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
if not artifacts:
    raise SystemExit('no packaged artifact found')
output = ROOT / 'target/verification'
output.mkdir(parents=True, exist_ok=True)
(output / 'summary.json').write_text(json.dumps({'actualXml': counts, 'artifacts': artifacts, 'remoteWorkflowExecuted': os.environ.get('GITHUB_ACTIONS') == 'true'}, indent=2) + '\n')
print(json.dumps(counts))
