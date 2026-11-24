#!/usr/bin/env bash

# stdout is the release JSON. Status 4 means the API returned HTTP 404.
lookup_github_release() {
    local response status_line http_status gh_status=0
    response="$(gh api --include "repos/$GH_REPO/releases/tags/$RELEASE_TAG")" || gh_status=$?
    response="${response//$'\r'/}"
    status_line="${response%%$'\n'*}"
    if [[ "$status_line" =~ ^HTTP/[^[:space:]]+[[:space:]]+([0-9]{3})([[:space:]]|$) ]]; then
        http_status="${BASH_REMATCH[1]}"
        if [[ "$http_status" == 404 ]]; then
            return 4
        fi
        if [[ "$http_status" == 200 && "$gh_status" == 0 && "$response" == *$'\n\n'* ]]; then
            printf '%s\n' "${response#*$'\n\n'}"
            return 0
        fi
    fi
    printf '::error::Could not look up GitHub Release %s (HTTP %s).\n' "$RELEASE_TAG" "${http_status:-unknown}" >&2
    return 1
}
