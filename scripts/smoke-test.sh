#!/usr/bin/env bash
# Walks the full flow and every CRUD endpoint against a running instance.
#
#   BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh
#   BASE_URL=http://country-info.local ./scripts/smoke-test.sh
#
# Needs curl and jq. Requires the real SOAP service to be reachable from the app.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="${BASE_URL}/api/v1/countries"
CID="smoke-$(date +%s)"
PASS=0

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

# call METHOD URL [BODY] -> sets STATUS and BODY
call() {
  local method="$1" url="$2" data="${3:-}"
  local out
  if [[ -n "${data}" ]]; then
    out="$(curl -sS -w '\n%{http_code}' -X "${method}" "${url}" \
      -H 'Content-Type: application/json' -H "X-Correlation-Id: ${CID}" -d "${data}")"
  else
    out="$(curl -sS -w '\n%{http_code}' -X "${method}" "${url}" -H "X-Correlation-Id: ${CID}")"
  fi
  STATUS="$(tail -n1 <<<"${out}")"
  BODY="$(sed '$d' <<<"${out}")"
}

expect() {
  local want="$1" what="$2"
  if [[ "${STATUS}" != "${want}" ]]; then
    echo "FAIL: ${what}: expected ${want}, got ${STATUS}" >&2
    echo "${BODY}" >&2
    exit 1
  fi
  PASS=$((PASS + 1))
  echo "ok   ${what} (${STATUS})"
}

echo "Smoke testing ${API} (correlation id ${CID})"

# Clean slate for Kenya so the test is repeatable.
call GET "${API}?size=100"
existing="$(jq -r '.content[] | select(.isoCode=="KE") | .id' <<<"${BODY}")"
if [[ -n "${existing}" ]]; then call DELETE "${API}/${existing}"; fi

call POST "${API}" '{"name":"kenya"}'
expect 201 "create kenya"
ID="$(jq -r '.id' <<<"${BODY}")"
[[ "$(jq -r '.isoCode' <<<"${BODY}")" == "KE" ]] || { echo "FAIL: isoCode" >&2; exit 1; }
[[ "$(jq -r '.capitalCity' <<<"${BODY}")" == "Nairobi" ]] || { echo "FAIL: capital" >&2; exit 1; }
echo "     -> id=${ID} $(jq -c '{isoCode,capitalCity,phoneCode,currencyIsoCode,languages:[.languages[].name]}' <<<"${BODY}")"

call POST "${API}" '{"name":"KENYA"}'
expect 409 "duplicate create is rejected"

call POST "${API}" '{"name":"atlantis"}'
expect 404 "unknown country"

call POST "${API}" '{"name":"k3nya"}'
expect 400 "invalid name"

call GET "${API}/${ID}"
expect 200 "get by id"

call GET "${API}?page=0&size=5&sort=name,asc"
expect 200 "list (paged)"

call PUT "${API}/${ID}" '{"name":"Kenya","capitalCity":"Nairobi","phoneCode":"254","continentCode":"AF","currencyIsoCode":"KES","flagUrl":"http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Kenya.jpg","languages":[{"isoCode":"sw","name":"Swahili"},{"isoCode":"en","name":"English"}]}'
expect 200 "update"

call DELETE "${API}/${ID}"
expect 204 "delete"

call GET "${API}/${ID}"
expect 404 "get after delete"

echo "All ${PASS} checks passed. Trace this run in the logs with correlationId=${CID}"
