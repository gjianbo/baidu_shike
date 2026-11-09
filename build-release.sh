#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

gradle_args=(:app:lintDebug :app:assembleRelease)
while (( $# > 0 )); do
    case "$1" in
        --version)
            if [[ ! "${2:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
                printf '用法：%s --version 0.1.24\n' "$0" >&2
                exit 2
            fi
            gradle_args+=("-PbdplayerVersionName=$2")
            shift 2
            ;;
        *)
            gradle_args+=("$1")
            shift
            ;;
    esac
done

if [[ -x ./gradlew ]]; then
    gradle=./gradlew
elif [[ -x /opt/homebrew/opt/gradle@8/bin/gradle ]]; then
    gradle=/opt/homebrew/opt/gradle@8/bin/gradle
elif command -v gradle >/dev/null; then
    gradle=gradle
else
    printf '找不到 Gradle，请安装 Gradle 8。\n' >&2
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
apksigner="$sdk_dir/build-tools/37.0.0/apksigner"
if [[ ! -x "$apksigner" ]]; then
    printf '找不到 apksigner，请配置 Android SDK 并安装 Build Tools 37.0.0。\n' >&2
    exit 1
fi

"$gradle" "${gradle_args[@]}"

release_dir=app/build/outputs/apk/release
version=$(jq -er '.elements[0].versionName' "$release_dir/output-metadata.json")
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    printf '无效的版本号：%s\n' "$version" >&2
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
