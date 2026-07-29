#!/usr/bin/env bash
set -euo pipefail

# Creates a small, repeatable demo workspace through the authenticated Java API.
# Required: DEMO_PASSWORD. Optional: STUDYPILOT_BASE_URL, DEMO_EMAIL_DOMAIN,
# DEMO_DISPLAY_NAME and DEMO_TARGET_DATE.

for command_name in curl jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 1
  }
done

: "${DEMO_PASSWORD:?Set DEMO_PASSWORD for the generated demo account}"

base_url="${STUDYPILOT_BASE_URL:-http://127.0.0.1:8080}"
email_domain="${DEMO_EMAIL_DOMAIN:-example.com}"
display_name="${DEMO_DISPLAY_NAME:-StudyPilot Demo}"
run_id="$(date +%s)-$RANDOM"
demo_email="studypilot-demo-${run_id}@${email_domain}"

if [[ -n "${DEMO_TARGET_DATE:-}" ]]; then
  target_date="$DEMO_TARGET_DATE"
elif date -v+180d +%F >/dev/null 2>&1; then
  target_date="$(date -v+180d +%F)"
else
  target_date="$(date -d '+180 days' +%F)"
fi

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/studypilot-demo.XXXXXX")"
trap 'rm -rf "$temp_dir"' EXIT

request() {
  local method="$1"
  local path="$2"
  local body="$3"
  local output_file="$4"
  local auth_header="${5:-}"
  local status
  local args=(-sS -o "$output_file" -w '%{http_code}' -X "$method"
    -H 'Content-Type: application/json')
  if [[ -n "$auth_header" ]]; then
    args+=(-H "Authorization: Bearer ${auth_header}")
  fi
  status="$(curl "${args[@]}" --data "$body" "${base_url}${path}")"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "${method} ${path} failed with HTTP ${status}" >&2
    exit 1
  fi
}

register_body="$(jq -cn \
  --arg email "$demo_email" \
  --arg password "$DEMO_PASSWORD" \
  --arg displayName "$display_name" \
  '{email: $email, password: $password, displayName: $displayName}')"
request POST /api/auth/register "$register_body" "$temp_dir/register.json"
access_token="$(jq -er '.accessToken' "$temp_dir/register.json")"

goal_body="$(jq -cn \
  --arg title '完成 StudyPilot Java + AI 学习闭环' \
  --arg targetDate "$target_date" \
  '{title: $title, targetDate: $targetDate, weeklyStudyHours: 8}')"
request POST /api/learning-goals "$goal_body" "$temp_dir/goal.json" "$access_token"
goal_id="$(jq -er '.id' "$temp_dir/goal.json")"

material_body="$(jq -cn \
  --arg title 'StudyPilot 演示课程大纲' \
  --arg content '第一章 Java 与 Spring Boot；第二章 FastAPI 与服务通信；第三章 RAG 与来源引用；第四章 Agent 授权、预览和确认。请按章节顺序学习。' \
  '{title: $title, content: $content, category: "SYLLABUS", privacyLevel: "NORMAL"}')"
request POST /api/materials/text "$material_body" "$temp_dir/material.json" "$access_token"
material_id="$(jq -er '.id' "$temp_dir/material.json")"

echo "Demo data created successfully."
echo "Email: ${demo_email}"
echo "Goal ID: ${goal_id}"
echo "Material ID: ${material_id}"
echo "Password and access token were not printed."
