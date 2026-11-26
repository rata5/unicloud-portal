set -euo pipefail

PROJECT="unicloud-public"

: "${CLOUDFLARE_API_TOKEN:?Set CLOUDFLARE_API_TOKEN (see the top of this script)}"
: "${CLOUDFLARE_ACCOUNT_ID:?Set CLOUDFLARE_ACCOUNT_ID (see the top of this script)}"
API="https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/pages/projects"

call() {  
  curl -sS -X "$1" "$2" -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" -H "Content-Type: application/json" \
    ${3:+--data "$3"}
}

answer=$(call GET "${API}/${PROJECT}")
if ! grep -q '"success": *true' <<< "$answer"; then
  echo "Creating the Pages project ${PROJECT}..."
  answer=$(call POST "$API" "{\"name\": \"${PROJECT}\", \"production_branch\": \"main\"}")
  if ! grep -q '"success": *true' <<< "$answer"; then
    echo "Cloudflare refused: $answer" >&2
    exit 1
  fi
fi

subdomain=$(grep -o '"subdomain": *"[^"]*"' <<< "$answer" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')