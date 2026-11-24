#!/usr/bin/env bash
set -euo pipefail

scripts="$(cd -- "$(dirname -- "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
cd -- "$tmp"
mkdir dist
touch dist/baidu-shike-9.0.0-release.apk

cat > gh <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
    "api --include repos/$GH_REPO/releases/tags/$RELEASE_TAG")
        echo lookup >> "$MOCK_LOG"
        if [[ "$MOCK_HTTP" == transport ]]; then
            printf '%s\n' 'transport failure (HTTP 404)' >&2
            exit 1
        fi
        printf 'HTTP/2.0 %s Mock\nContent-Type: application/json\r\n\r\n' "$MOCK_HTTP"
        printf '%s\n' "$MOCK_RELEASE"
        if [[ "$MOCK_HTTP" != 200 ]]; then
            printf '%s\n' 'The CLI error message does not determine the HTTP status' >&2
            exit 1
        fi ;;
    "api repos/$GH_REPO/git/ref/tags/$RELEASE_TAG --jq .object.sha")
        echo tag >> "$MOCK_LOG"
        [[ -n "$MOCK_REMOTE_TAG" ]] || exit 1
        printf '%s\n' "$MOCK_REMOTE_TAG" ;;
    "release upload $RELEASE_TAG dist/baidu-shike-9.0.0-release.apk")
        echo upload >> "$MOCK_LOG" ;;
    "release create --verify-tag --title 拾课 $RELEASE_TAG --generate-notes -- $RELEASE_TAG dist/baidu-shike-9.0.0-release.apk")
        echo create >> "$MOCK_LOG" ;;
    *)
        printf 'Unexpected gh call: %s\n' "$*" >&2
        exit 99 ;;
esac
MOCK
chmod +x gh
export PATH="$tmp:$PATH" GH_REPO=example/project RELEASE_TAG=v9.0.0
export BUILD_SOURCE_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export BUILD_TAG_OBJECT_SHA=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
export MOCK_LOG="$tmp/calls"

run_case() {
    local label="$1" status="$2" remote_tag="$3" release="$4" result="$5" expected_calls="$6"
    local exit_code=0
    : > "$MOCK_LOG"
    MOCK_HTTP="$status" MOCK_REMOTE_TAG="$remote_tag" MOCK_RELEASE="$release" \
        bash "$scripts/publish-android-release.sh" > "$tmp/stdout" 2> "$tmp/stderr" || exit_code=$?
    if [[ "$exit_code" != "$result" || "$(paste -sd ' ' "$MOCK_LOG")" != "$expected_calls" ]]; then
        printf 'FAIL %s (exit %s, calls: %s)\n' "$label" "$exit_code" "$(paste -sd ' ' "$MOCK_LOG")" >&2
        cat "$tmp/stdout" "$tmp/stderr" >&2
        exit 1
    fi
    printf 'PASS %s\n' "$label"
}

empty='{"assets":[]}'
uploaded='{"assets":[{"name":"baidu-shike-9.0.0-release.apk","state":"uploaded"}]}'
incomplete='{"assets":[{"name":"baidu-shike-9.0.0-release.apk","state":"starter"}]}'
run_case 'HTTP 404 creates a Release after checking the tag' 404 "$BUILD_TAG_OBJECT_SHA" '{}' 0 'lookup tag create'
run_case 'missing APK is uploaded for an annotated tag' 200 "$BUILD_TAG_OBJECT_SHA" "$empty" 0 'lookup tag upload'
run_case 'uploaded APK is preserved' 200 "$BUILD_TAG_OBJECT_SHA" "$uploaded" 0 'lookup tag'
run_case 'incomplete APK fails without uploading' 200 "$BUILD_TAG_OBJECT_SHA" "$incomplete" 1 'lookup'
run_case 'moved tag stops creation' 404 "$BUILD_SOURCE_SHA" '{}' 1 'lookup tag'
run_case 'moved tag stops upload' 200 "$BUILD_SOURCE_SHA" "$empty" 1 'lookup tag'
run_case 'deleted tag stops upload' 200 '' "$empty" 1 'lookup tag'
run_case 'HTTP 403 stops publication' 403 "$BUILD_TAG_OBJECT_SHA" '{}' 1 'lookup'
run_case 'HTTP 500 stops publication' 500 "$BUILD_TAG_OBJECT_SHA" '{}' 1 'lookup'
run_case 'transport failure is not an HTTP 404' transport "$BUILD_TAG_OBJECT_SHA" '{}' 1 'lookup'
