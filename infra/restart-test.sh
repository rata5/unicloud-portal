set -euo pipefail
: "${PORTAL_URL:?Set PORTAL_URL, STUDENT_EMAIL and STUDENT_PASSWORD}" "${STUDENT_EMAIL:?}" "${STUDENT_PASSWORD:?}"
RG="unicloud-rg"            
APP="unicloud-portal"
JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

echo "Signing in as $STUDENT_EMAIL..."
csrf=$(curl -fsS -c "$JAR" -b "$JAR" "$PORTAL_URL/login" | sed -n 's/.*name="csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -fsS -c "$JAR" -b "$JAR" -o /dev/null -X POST "$PORTAL_URL/login" \
  --data-urlencode "csrf=$csrf" --data-urlencode "email=$STUDENT_EMAIL" --data-urlencode "password=$STUDENT_PASSWORD"

get() { 
  local body
  if body=$(curl -fsS -b "$JAR" -H 'Accept: application/json' "$PORTAL_URL$1" 2>&1); then
    jq -S "$2" <<< "$body" 2>/dev/null || echo '"not JSON"'
  else
    jq -n --arg error "$body" '"request failed: " + $error'
  fi
}

snapshot() {
  local documents='"not checked (DOCUMENTS_PATH is not set)"'
  if [[ -n "${DOCUMENTS_PATH:-}" ]]; then
    documents=$(get "$DOCUMENTS_PATH" '.')
  fi
  jq -n -S --argjson me "$(get /api/v1/portal/me '{user, csrf}')" \
    --argjson enrollments "$(get /api/v1/portal/enrollments '[.[] | {id, code, status}]')" \
    --argjson documents "$documents" '{me: $me, enrollments: $enrollments, documents: $documents}'
}

before=$(snapshot)
if [[ "$(jq -r '.me | type' <<< "$before")" != "object" ]]; then
  echo "Signing in did not work: $(jq -r '.me' <<< "$before")" >&2
  exit 1
fi
echo "Before: $(jq '.enrollments | length' <<< "$before") enrollments, documents: $(jq -c '.documents | if type == "array" then length else . end' <<< "$before")"

revision=$(az containerapp revision list -n "$APP" -g "$RG" --query "[?properties.active].name | [0]" -o tsv)
replicas=$(az containerapp replica list -n "$APP" -g "$RG" --revision "$revision" --query "[].name" -o tsv | sort | tr '\n' ' ')
echo "Revision $revision, replicas: $replicas"
echo "Restarting every replica..."
az containerapp revision restart -n "$APP" -g "$RG" --revision "$revision" --output none

for attempt in $(seq 1 60); do
  now=$(az containerapp replica list -n "$APP" -g "$RG" --revision "$revision" --query "[].name" -o tsv | sort | tr '\n' ' ')
  served=$(curl -fsS -H 'Accept: application/json' "$PORTAL_URL/" 2>/dev/null | jq -r '.replica' || true)
  if [[ -n "$now" && "$now" != "$replicas" && -n "$served" && "$replicas" != *"$served"* ]]; then
    echo "New replicas: $now (this answer came from $served)"
    break
  fi
  if [[ $attempt -eq 60 ]]; then echo "The replicas did not change within 5 minutes" >&2; exit 1; fi
  sleep 5
done

after=$(snapshot)
if [[ "$before" == "$after" ]]; then
  echo "Nothing lost: the same session is still signed in, and everything the student saw is still there."
else
  echo "Something changed:" >&2
  diff <(echo "$before") <(echo "$after") >&2 || true
  exit 1
fi