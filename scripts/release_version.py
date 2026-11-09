#!/usr/bin/env python3
"""Select a monotonic release version; reruns reuse the same commit's release."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

TAG = re.compile(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)')
CODE = re.compile(r'<!-- colorcode-version-code: ([1-9]\d*) -->')


def select_version(releases, commit, tag_commits, requested=None):
    if requested is not None and (not isinstance(requested, str) or not TAG.fullmatch('v' + requested)):
        raise ValueError('Requested version must be a semantic version such as 1.0.0')
    stable = [r for r in releases if not r.get('draft') and not r.get('prerelease') and TAG.fullmatch(r['tag_name'])]
    stable.sort(key=lambda r: tuple(map(int, TAG.fullmatch(r['tag_name']).groups())))
    codes = []
    for release in stable:
        marker = CODE.search(release.get('body') or '')
        if not marker:
            raise ValueError('Missing version code marker for ' + release['tag_name'])
        codes.append(int(marker[1]))
    if len(codes) != len(set(codes)) or any(a >= b for a, b in zip(codes, codes[1:])):
        raise ValueError('Release version codes must strictly increase')
    for release, code in zip(stable, codes):
        if tag_commits.get(release['tag_name']) == commit:
            return release['tag_name'], code
    latest = tuple(map(int, TAG.fullmatch(stable[-1]['tag_name']).groups())) if stable else None
    requested_parts = tuple(map(int, requested.split('.'))) if requested else None
    if requested_parts and (latest is None or requested_parts > latest):
        tag = 'v' + requested
    elif requested_parts and not any(r['tag_name'] == 'v' + requested for r in stable):
        raise ValueError('Requested version must exceed the latest release')
    elif latest:
        tag = f'v{latest[0]}.{latest[1]}.{latest[2] + 1}'
    else:
        tag = 'v0.1.0'
    code = (codes[-1] + 1) if codes else 1
    if code > 2_100_000_000:
        raise ValueError('Android version code exceeds the maximum')
    return tag, code


def main():
    pages = json.loads(Path(sys.argv[1]).read_text())
    releases = [r for page in pages for r in page]
    commits = {}
    for release in releases:
        tag = release['tag_name']
        if not release.get('draft') and not release.get('prerelease') and TAG.fullmatch(tag):
            commits[tag] = subprocess.check_output(['git', 'rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}'], text=True).strip()
    requested = json.loads(Path('.github/release-version.json').read_text())['next_version']
    tag, code = select_version(releases, os.environ['GITHUB_SHA'], commits, requested)
    with open(os.environ['GITHUB_ENV'], 'a') as env:
        env.write(f'COLORCODE_VERSION_NAME={tag[1:]}\nCOLORCODE_VERSION_CODE={code}\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'tag={tag}\ncode={code}\n')

if __name__ == '__main__':
    main()
