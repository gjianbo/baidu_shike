#!/usr/bin/env bash
# versionCode 严格递增门禁。
#
# 基线是仓库里已发布的 v* tag（CI 里需要 checkout fetch-depth: 0）。
# 规则：
#   1. versionCode 必须是正整数；
#   2. 不允许小于历史 tag 的最大 versionCode（版本回退）；
#   3. versionName 变了、或要发布一个新 tag，versionCode 必须严格大于历史最大值；
#   4. --rebuild 用于 workflow_dispatch 重发已有 tag：只校验 code 与该 tag 一致。
#
# 用法：
#   check-version-code.sh [--repo DIR] [--version-name NAME]
#                         [--release-tag TAG] [--rebuild]
# 环境变量：RELEASE_TAG（CI 的 job env 里已有）、GITHUB_ACTIONS（决定报错前缀）。
set -euo pipefail

usage() {
    cat <<'EOF'
用法：check-version-code.sh [选项]
  --repo DIR            仓库根目录，默认取脚本所在仓库
  --version-name NAME   实际发版的 versionName（本地 build-release.sh --version）
                        默认：RELEASE_TAG 去掉 v，或 build.gradle 里的默认值
  --release-tag TAG     本次发布的 tag，默认取环境变量 RELEASE_TAG
  --rebuild             重发已有 tag（workflow_dispatch），跳过递增要求
  -h, --help            显示帮助
EOF
}

fail() {
    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        printf '::error::%s\n' "$1" >&2
    else
        printf '错误：%s\n' "$1" >&2
    fi
    exit 1
}

repo=""
version_name=""
release_tag="${RELEASE_TAG:-}"
rebuild=0

while (( $# > 0 )); do
    case "$1" in
        --repo)
            repo="${2:-}"; shift 2 ;;
        --version-name)
            version_name="${2:-}"; shift 2 ;;
        --release-tag)
            release_tag="${2:-}"; shift 2 ;;
        --rebuild)
            rebuild=1; shift ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            printf '未知参数：%s\n' "$1" >&2
            usage >&2
            exit 2 ;;
    esac
done

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "$repo" ]]; then
    repo="$(cd -- "$script_dir/../.." && pwd)"
fi
gradle_file="$repo/app/build.gradle"
[[ -f "$gradle_file" ]] || fail "找不到 $gradle_file"

# build.gradle 的 versionCode 形如 "versionCode 24"，versionName 可能写成
# "versionName '0.1.20'" 或 "versionName providers...getOrElse('1.0.1')"。
extract_code() {
    sed -n 's/^[[:space:]]*versionCode[[:space:]]\{1,\}\([0-9][0-9]*\).*/\1/p' | head -n 1
}

extract_name() {
    local line name
    line="$(grep -m1 '^[[:space:]]*versionName' || true)"
    name="$(printf '%s\n' "$line" \
        | sed -n "s/.*getOrElse(['\"]\([^'\"]*\)['\"]).*/\1/p" | head -n 1 || true)"
    if [[ -z "$name" ]]; then
        name="$(printf '%s\n' "$line" \
            | grep -oE "'[^']*'|\"[^\"]*\"" | head -n 1 | tr -d "'\"" || true)"
    fi
    printf '%s' "$name"
}

# 用 "${tag}:" 而不是 "$tag:" —— zsh 会把 ":a" 当成路径修饰符，
# 在交互式排查时很容易把 tag 名拼坏，写全花括号最稳。
tag_code() {
    local tag="$1" content
    # 浅克隆里 tag 名还在、提交对象却取不到，绝不能当成「没有历史」静默放行。
    if ! git -C "$repo" cat-file -e "${tag}^{commit}" 2>/dev/null; then
        fail "读不到 tag $tag 的提交对象；CI 的 actions/checkout 需要 fetch-depth: 0"
    fi
    content="$(git -C "$repo" cat-file -p "${tag}:app/build.gradle" 2>/dev/null || true)"
    [[ -n "$content" ]] || return 0
    printf '%s\n' "$content" | extract_code
}

current_code="$(extract_code < "$gradle_file")"
[[ "$current_code" =~ ^[0-9]+$ ]] || fail "app/build.gradle 里没有合法的 versionCode 数字"
if (( current_code <= 0 )); then
    fail "versionCode 必须是正整数，当前是 $current_code"
fi

if [[ -z "$version_name" ]]; then
    if [[ -n "$release_tag" ]]; then
        version_name="${release_tag#v}"
    else
        version_name="$(extract_name < "$gradle_file")"
    fi
fi
[[ -n "$version_name" ]] || fail "无法确定本次构建的 versionName"

# 重发已有 tag：不允许用另一份 versionCode 的代码重发，但也不要求递增。
if (( rebuild )); then
    [[ -n "$release_tag" ]] || fail "--rebuild 必须同时指定 --release-tag"
    published_code="$(tag_code "$release_tag")"
    [[ "$published_code" =~ ^[0-9]+$ ]] || fail "tag $release_tag 里读不到 versionCode"
    if [[ "$current_code" != "$published_code" ]]; then
        fail "重发 ${release_tag} 时 versionCode 必须保持 ${published_code}，当前是 ${current_code}"
    fi
    printf '版本门禁通过：重发 %s（versionCode=%s）\n' "$release_tag" "$current_code"
    exit 0
fi

baseline_tag=""
baseline_name=""
max_code=0
# 排序失败必须报错，不能让空列表伪装成「还没有发布过任何版本」。
tag_list="$(git -C "$repo" tag -l 'v[0-9]*' --sort=-version:refname 2>/dev/null)" \
    || fail "无法读取 v* tag 历史；请确认 git 可用且 CI 使用 fetch-depth: 0"
while IFS= read -r tag; do
    [[ -n "$tag" ]] || continue
    # 本次正在发布的新 tag 已经存在于 CI 的 checkout 里，不能拿它当自己的基线。
    [[ "$tag" == "$release_tag" ]] && continue
    [[ "$tag" =~ ^v[0-9] ]] || continue
    code="$(tag_code "$tag")"
    [[ "$code" =~ ^[0-9]+$ ]] || continue
    if [[ -z "$baseline_tag" ]]; then
        baseline_tag="$tag"
        baseline_name="${tag#v}"
    fi
    if (( code > max_code )); then
        max_code="$code"
    fi
done <<< "$tag_list"

if (( current_code < max_code )); then
    fail "versionCode 回退：当前 ${current_code}，已发布基线 ${baseline_tag:-无} 的最大值 ${max_code}"
fi
if [[ -n "$release_tag" ]] && (( current_code <= max_code )); then
    fail "发布 ${release_tag} 必须严格递增 versionCode：已发布最大值 ${max_code}，当前仍是 ${current_code}。请修改 app/build.gradle 的 versionCode"
fi
if [[ -n "$baseline_name" && "$version_name" != "$baseline_name" ]] \
    && (( current_code <= max_code )); then
    fail "versionName 从 ${baseline_name} 变成了 ${version_name}，但 versionCode 仍是 ${current_code}。请同步把 versionCode 加到 $((max_code + 1)) 以上"
fi

printf '版本门禁通过：versionName=%s versionCode=%s 基线=%s(code=%s)%s\n' \
    "$version_name" "$current_code" "${baseline_tag:-无}" "$max_code" \
    "${release_tag:+ 本次发布=$release_tag}"
