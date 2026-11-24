#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/github-release-api.sh"

: "${BUILD_SOURCE_SHA:?Missing build commit SHA}"
: "${BUILD_TAG_OBJECT_SHA:?Missing build tag object SHA}"

verify_release_tag() {
    local remote_tag_sha
    # Comparing the ref object also covers annotated tags.
    if ! remote_tag_sha="$(gh api "repos/$GH_REPO/git/ref/tags/$RELEASE_TAG" --jq '.object.sha')"; then
        echo "::error::Failed to resolve remote tag $RELEASE_TAG for build commit $BUILD_SOURCE_SHA."
        return 1
    fi
    if [[ "$remote_tag_sha" != "$BUILD_TAG_OBJECT_SHA" ]]; then
        echo "::error::Remote tag $RELEASE_TAG changed after checkout of $BUILD_SOURCE_SHA (expected $BUILD_TAG_OBJECT_SHA, found $remote_tag_sha)."
        return 1
    fi
}

version="${RELEASE_TAG#v}"
apk="dist/baidu-shike-$version-release.apk"
test -f "$apk"
if release="$(lookup_github_release)"; then
    asset_state="$(jq -r --arg name "${apk##*/}" \
        'first(.assets[] | select(.name == $name) | .state) // "missing"' <<< "$release")"
    case "$asset_state" in
        uploaded)
            verify_release_tag
            printf 'Release %s already has %s. Keeping the published asset.\n' "$RELEASE_TAG" "${apk##*/}" ;;
        missing)
            verify_release_tag
            gh release upload "$RELEASE_TAG" "$apk" ;;
        *)
            printf 'Release %s has an incomplete asset %s (state: %s).\n' "$RELEASE_TAG" "${apk##*/}" "$asset_state" >&2
            exit 1 ;;
    esac
elif [[ "$?" == 4 ]]; then
    verify_release_tag
    gh release create --verify-tag --title "拾课 $RELEASE_TAG" --generate-notes -- "$RELEASE_TAG" "$apk"
else
    exit 1
fi
