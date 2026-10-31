#!/usr/bin/env python3
"""Summarize live reports without treating missing answers or missing jobs as passes."""
import argparse
import collections
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('results', type=Path)
parser.add_argument('targets', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
reports = {}
for path in args.results.glob('shard-*/*.json'):
    value = json.loads(path.read_text())
    if 'cases' in value:
        reports[value['model']] = (path, value)
entries = []
for model in args.targets.read_text().splitlines():
    if model not in reports:
        entries.append({'model': model, 'status': 'MISSING'})
        continue
    path, value = reports[model]
    cases = value['cases']
    expected = 1 if 'needle2' in model else 3
    if value.get('failure'):
        status = 'RUNTIME_FAILURE'
    elif not value.get('complete') or len(cases) != expected:
        status = 'INCOMPLETE'
    elif any(case['reasoningMarkerLeak'] for case in cases):
        status = 'REASONING_MARKER_LEAK'
    elif any(not case['hasFinalAnswer'] for case in cases):
        status = 'NO_FINAL_ANSWER'
    elif any(case.get('stopReason') not in ('EOS', 'STOP_SEQUENCE', 'CONSTRAINT_COMPLETE') for case in cases):
        status = 'TRUNCATED'
    else:
        status = 'VISIBLE_ANSWERS_NO_REASONING_MARKERS'
    entries.append({'model': model, 'status': status, 'report': str(path),
                    'failure': value.get('failure'),
                    'visibleAnswers': [case['visible'] for case in cases],
                    'stopReasons': [case.get('stopReason') for case in cases]})
result = {'scope': 'Default demo output, three prompts per chat model and one Needle 2 tool request. '
                   'Marker detection is automated; unmarked reasoning and answer accuracy require review. '
                   'This does not establish absence of reasoning leakage for all possible prompts.',
          'counts': dict(collections.Counter(entry['status'] for entry in entries)), 'entries': entries}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result['counts']))
