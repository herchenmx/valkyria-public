#!/usr/bin/env bash
# Promote api-versions/candidate.json to active.json after a successful probe
# of the new official-Hevy version/build against the private API.
#
# Usage: ./.github/scripts/promote-candidate.sh
#
# Run this only after you've verified the new (version_name, version_code)
# does not break private-API requests. The companion app reads active.json
# from raw.githubusercontent.com on launch and propagates it to the watch
# via DataClient.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [[ ! -f api-versions/candidate.json ]]; then
  echo "No api-versions/candidate.json to promote — nothing to do."
  exit 0
fi

version=$(jq -r .version_name api-versions/candidate.json)
build=$(jq -r .version_code api-versions/candidate.json)
detected_at=$(jq -r .detected_at api-versions/candidate.json)
source_url=$(jq -r .source_url api-versions/candidate.json)
promoted_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

jq -n \
  --arg v "$version" \
  --arg b "$build" \
  --arg p "$promoted_at" \
  --arg s "$source_url" \
  '{version_name: $v, version_code: $b, promoted_at: $p, source_url: $s}' \
  > api-versions/active.json

# Stamp the matching history entry with the promotion time.
jq --arg v "$version" --arg b "$build" --arg p "$promoted_at" '
  .entries |= map(
    if .version_name == $v and .version_code == $b
    then . + {promoted_at: $p}
    else . end
  )
' api-versions/history.json > api-versions/history.json.tmp
mv api-versions/history.json.tmp api-versions/history.json

rm api-versions/candidate.json

echo "Promoted ${version} (${build}). Commit + push to publish to companion:"
echo "  git add api-versions/ && git commit -m 'Promote ${version} (${build})' && git push"
