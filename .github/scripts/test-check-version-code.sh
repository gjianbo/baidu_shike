#!/usr/bin/env bash
# check-version-code.sh 的场景自测：在临时 git 仓库里造 tag 历史，
# 断言每种版本组合应该通过还是被拦。不碰真实仓库，也不需要 Android SDK。
set -euo pipefail
unset RELEASE_TAG GITHUB_ACTIONS

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
guard="$script_dir/check-version-code.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/bdplayer-version-guard.XXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT
repo="$tmp/repo"
mkdir -p "$repo/app"
cd -- "$repo"
git init -q
git config user.email guard@example.com
git config user.name guard

write_gradle() {
    cat > app/build.gradle <<EOF
android {
    defaultConfig {
        versionCode $1
        versionName providers.gradleProperty('bdplayerVersionName').getOrElse('$2')
    }
}
EOF
}

commit_tag() {
    git add app/build.gradle
    git commit -qm "$1"
    if [[ -n "${2:-}" ]]; then
        git tag "$2"
    fi
}

passes=0
failures=0
err_log="$tmp/stderr"

expect_pass() {
    local label="$1"; shift
    if bash "$guard" --repo "$repo" "$@" >/dev/null 2>"$err_log"; then
        printf '  PASS  %s\n' "$label"
        passes=$((passes + 1))
    else
        printf '  FAIL  %s（期望通过，实际被拦）\n' "$label" >&2
        sed 's/^/        /' "$err_log" >&2
        failures=$((failures + 1))
    fi
}

# 失败场景必须确认「是门禁规则拦下的」，不能只看出错退出：
# bash 变量拼错、命令不存在也会退出非 0，那样自测会假绿。
expect_fail() {
    local label="$1"; shift
    local match=""
    if [[ "${1:-}" == "--match" ]]; then
        match="${2:-}"
        shift 2
    fi
    if bash "$guard" --repo "$repo" "$@" >/dev/null 2>"$err_log"; then
        printf '  FAIL  %s（期望被拦，实际通过）\n' "$label" >&2
        failures=$((failures + 1))
    elif ! grep -q '^错误：' "$err_log"; then
        printf '  FAIL  %s（被拦但不是门禁判定，疑似脚本崩溃）\n' "$label" >&2
        sed 's/^/        /' "$err_log" >&2
        failures=$((failures + 1))
    elif [[ -n "$match" ]] && ! grep -qF -- "$match" "$err_log"; then
        printf '  FAIL  %s（错误信息不含 %s）\n' "$label" "$match" >&2
        sed 's/^/        /' "$err_log" >&2
        failures=$((failures + 1))
    else
        printf '  PASS  %s\n' "$label"
        passes=$((passes + 1))
    fi
}

printf '版本门禁场景自测（%s）\n' "$repo"

# 首次构建：仓库里还没有任何 tag。
write_gradle 1 0.1.0
expect_pass "无历史 tag 的首次构建"

# 复刻真实发布历史：v0.1.24 与 v1.0.1 共用 versionCode 24。
write_gradle 21 0.1.20; commit_tag "release 0.1.20" v0.1.20
write_gradle 22 0.1.21; commit_tag "release 0.1.21" v0.1.21
write_gradle 24 0.1.23; commit_tag "release 0.1.24" v0.1.24
write_gradle 24 1.0.1;  commit_tag "release 1.0.1"  v1.0.1

# 当前状态与最新 tag 完全一致：日常构建不能误伤。
expect_pass "日常构建：versionName 与 code 都与最新 tag 一致"
expect_pass "本地重打同版本" --version-name 1.0.1

# 核心风险：换了版本名却没涨 code（本地 -P 覆盖 / CI 打新 tag）。
expect_fail "本地 --version 换名但 code 未涨" --match "versionName 从" --version-name 1.0.2
expect_fail "CI 发布新 tag 但 code 未涨" --match "严格递增" --release-tag v1.0.2

write_gradle 23 1.0.1
expect_fail "versionCode 回退到 23" --match "回退"
write_gradle abc 1.0.1
expect_fail "versionCode 不是数字" --match "合法的 versionCode"
write_gradle 24 1.0.1

expect_pass "重发已有 tag" --release-tag v1.0.1 --rebuild
write_gradle 25 1.0.1
expect_fail "重发已有 tag 却改了 code" --match "必须保持" --release-tag v1.0.1 --rebuild

# 正常递增：打完 tag 后基线会推进到 25。
write_gradle 25 1.0.2; commit_tag "release 1.0.2" v1.0.2
expect_pass "新 tag 且 code 递增" --release-tag v1.0.2
expect_pass "递增后的日常构建"
expect_fail "再发 v1.0.3 但 code 仍是 25" --match "严格递增" --release-tag v1.0.3

# 历史里混进读不到 versionCode 的 tag，不能让门禁误判。
cat > app/build.gradle <<'EOF'
android {
    defaultConfig {
        versionName '9.9.9'
    }
}
EOF
commit_tag "tag without versionCode" v9.9.9
write_gradle 25 1.0.2
expect_pass "历史 tag 缺 versionCode 时跳过该基线"

printf '\n结果：%d 通过 / %d 失败\n' "$passes" "$failures"
if (( failures > 0 )); then
    exit 1
fi
