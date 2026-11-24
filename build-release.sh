#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$project_dir"

usage() {
    printf '用法：%s [--rebuild] [--tag vX.Y.Z | --version X.Y.Z]\n' "$0"
}

rebuild=0
if [[ "${1:-}" == --rebuild ]]; then
    rebuild=1
    shift
fi
case "$#" in
    0) release_tag="$(git describe --exact-match --tags --match 'v[0-9]*' HEAD 2>/dev/null || true)" ;;
    1)
        if [[ "$1" == -h || "$1" == --help ]]; then
            usage
            exit 0
        fi
        usage >&2
        exit 2
        ;;
    2)
        case "$1" in
            --tag) release_tag="$2" ;;
            --version) release_tag="v$2" ;;
            *) usage >&2; exit 2 ;;
        esac
        ;;
    *) usage >&2; exit 2 ;;
esac
if [[ ! "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    printf '正式包需要已有的 Git tag（vX.Y.Z）。\n' >&2
    usage >&2
    exit 2
fi
if ! git rev-parse --verify "refs/tags/${release_tag}^{commit}" >/dev/null 2>&1; then
    printf '找不到 Git tag %s。\n' "$release_tag" >&2
    exit 1
fi
control_sha="$(git rev-parse --verify HEAD)"

# 在临时克隆中构建，工作区的未提交改动和忽略文件不会成为构建输入。
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/bdplayer-release.XXXXXX")"
trap 'rm -rf -- "$build_dir"' EXIT
source_dir="$build_dir/source"
git clone --quiet --shared --no-checkout "$project_dir" "$source_dir"
git -C "$source_dir" checkout --quiet --detach "refs/tags/$release_tag"
set -- --repo "$source_dir" --release-tag "$release_tag"
if (( rebuild )); then
    set -- "$@" --rebuild
fi
git show "${control_sha}:.github/scripts/check-version-code.sh" > "$build_dir/guard.sh"
bash "$build_dir/guard.sh" "$@"

# SDK 和签名配置由调用方提供，仓库里只读取 tag 的文件。
if [[ -f local.properties ]]; then
    ln -s "$project_dir/local.properties" "$source_dir/local.properties"
fi
if [[ -x "$source_dir/gradlew" ]]; then
    gradle="$source_dir/gradlew"
elif [[ -x /opt/homebrew/opt/gradle@8/bin/gradle ]]; then
    gradle=/opt/homebrew/opt/gradle@8/bin/gradle
elif command -v gradle >/dev/null; then
    gradle="$(command -v gradle)"
else
    printf '找不到 Gradle，请安装 Gradle 8.13 或以上。\n' >&2
    exit 1
fi

if ! command -v jq >/dev/null; then
    printf '找不到 jq，请先安装。\n' >&2
    exit 1
fi

sdk_dir=""
if [[ -f local.properties ]]; then
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' local.properties | head -n 1)
fi
sdk_dir="${sdk_dir:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
apksigner="$sdk_dir/build-tools/35.0.0/apksigner"
if [[ ! -x "$apksigner" ]]; then
    printf '找不到 apksigner，请配置 Android SDK 并安装 Build Tools 35.0.0。\n' >&2
    exit 1
fi

(cd -- "$source_dir" && "$gradle" --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleRelease)

release_dir="$source_dir/app/build/outputs/apk/release"
version=$(jq -er '.elements[0].versionName' "$release_dir/output-metadata.json")
if [[ "$release_tag" != "v$version" ]]; then
    printf 'APK 版本号 %s 与 Git tag %s 不一致。\n' "$version" "$release_tag" >&2
    exit 1
fi

apk="$release_dir/app-release.apk"
if [[ ! -f "$apk" ]] || ! "$apksigner" verify "$apk"; then
    printf '正式包签名验证失败，请检查正式签名配置。\n' >&2
    exit 1
fi

output_dir="$PWD/build/releases"
mkdir -p "$output_dir"
output="$output_dir/baidu-shike-$version-release.apk"
cp "$apk" "$output"
printf '正式包已生成：%s\n' "$output"
