#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo_dir/scripts/demo-data.sh"
http_file="$repo_dir/docs/stage8-release-e2e.http"

test -x "$script"
test -f "$http_file"
bash -n "$script"

# The demo must stay on the authenticated public Java boundary.
if grep -Eq '/internal/|X-Internal-Service-Token|ownerId' "$script"; then
  echo "demo-data.sh must not call internal APIs or accept ownerId" >&2
  exit 1
fi

# Secrets and tokens may be read from environment/response, but never printed.
if grep -Eq 'echo .*(TOKEN|PASSWORD|API_KEY)|printf .*(TOKEN|PASSWORD|API_KEY)' "$script"; then
  echo "demo-data.sh may not print credentials or bearer tokens" >&2
  exit 1
fi

for endpoint in \
  '/api/auth/register' \
  '/api/learning-goals' \
  '/api/materials/text'
do
  grep -Fq "$endpoint" "$script"
done

if grep -Eq 'X-Internal-Service-Token|/internal/|"ownerId"' "$http_file"; then
  echo "stage8-release-e2e.http must stay on the public Java API" >&2
  exit 1
fi

for endpoint in \
  '/api/ai-settings' \
  '/api/agent/knowledge-conversations' \
  '/api/agent/plan-conversations' \
  '/api/agent/task-conversations' \
  '/api/agent/quizzes/generate' \
  '/api/quizzes/' \
  '/api/quiz-attempts/'
do
  grep -Fq "$endpoint" "$http_file"
done

grep -Fq 'FastAPI 重启恢复验证' "$http_file"
grep -Fq '{{demoPassword}}' "$http_file"

echo "demo-data.sh static contract checks passed"
