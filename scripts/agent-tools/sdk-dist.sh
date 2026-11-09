#!/bin/sh
# sdk-dist — 离线分发件打包包装器（原址 scripts/dist.sh 保留，见 docs skill 本仓例外）
# toolbox-script
# format: v1
# name: sdk-dist
# summary: 打包四个 AAR 到 dist/maven-repo 并打 zip；--json 为快速预检不执行发布，裸跑才执行
# trigger: manual
# platform: unix
# cat: deploy
# alias: sdkdist
# self-test: --self-test
#
# 用法: sdk-dist.sh [--help|--json|--dry-run|--self-test]
#   --help       显示帮助
#   --json       快速预检结论（不执行任务本体）
#   --dry-run    打印将执行的命令，不发布
#   --self-test  金丝雀自检（坏样本必须被拒）
#   裸跑         exec scripts/dist.sh，参数原样透传

set -u

FAILMSG=''
TARGET=''

resolve_root() {
    r=$(git rev-parse --show-toplevel 2>/dev/null) || r=''
    [ -n "$r" ] || r=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || r=''
    printf '%s' "$r"
}

precheck() {
    r=$1
    FAILMSG=''
    if [ ! -r "$r/VERSION" ]; then
        FAILMSG="VERSION 不可读"; return 1
    fi
    if ! grep -q '^version=' "$r/VERSION" 2>/dev/null; then
        FAILMSG="VERSION 缺 version 字段"; return 1
    fi
    if [ ! -x "$r/gradlew" ]; then
        FAILMSG="gradlew 不存在或不可执行"; return 1
    fi
    if [ ! -r "$r/scripts/dist.sh" ]; then
        FAILMSG="原址脚本 scripts/dist.sh 不存在"; return 1
    fi
    TARGET="$r/scripts/dist.sh"
    return 0
}

usage() {
    cat <<'EOF'
sdk-dist —— 打包 GoodHub SDK 离线分发件（薄包装 scripts/dist.sh）

用法: sdk-dist.sh [--help|--json|--dry-run|--self-test]

  --help       显示本帮助
  --json       快速预检结论：VERSION / gradlew / 原址脚本是否就位（不执行发布）
  --dry-run    打印将执行的命令后退出，不写 dist/
  --self-test  金丝雀自检：缺件样本必须被预检拒绝
  裸跑         执行 scripts/dist.sh（会跑四个发布任务并写 dist/，有副作用）

副作用提示：裸跑会写 dist/maven-repo 与 dist/*.zip（均被 .gitignore 忽略），
且触发 Gradle 构建，可能联网拉依赖。发布前先跑 --json 或 --dry-run。
EOF
}

ROOT=$(resolve_root)

case "${1:-}" in
    --help|-h)
        usage
        exit 0
        ;;
    --json)
        if precheck "$ROOT"; then
            printf '{"status":"OK","severity":"info","message":"离线分发件预检通过：VERSION / gradlew / scripts/dist.sh 就位"}\n'
            exit 0
        fi
        printf '{"status":"FAIL","severity":"error","message":"预检未通过：%s","remedy":"在仓库根运行并确认 VERSION 与 gradlew 就位"}\n' "$FAILMSG"
        exit 1
        ;;
    --dry-run|-n)
        if ! precheck "$ROOT"; then
            printf '[remedy] 预检未通过：%s\n' "$FAILMSG" >&2
            exit 1
        fi
        printf '[dry-run] exec %s\n' "$TARGET"
        exit 0
        ;;
    --self-test)
        if precheck /nonexistent-sdk-dist-canary >/dev/null 2>&1; then
            printf '{"status":"FAIL","severity":"error","message":"金丝雀失效：缺件样本未被拒绝"}\n'
            exit 1
        fi
        if ! precheck "$ROOT" >/dev/null 2>&1; then
            printf '{"status":"FAIL","severity":"error","message":"金丝雀失效：真实仓库预检未通过：%s"}\n' "$FAILMSG"
            exit 1
        fi
        printf '{"status":"OK","severity":"info","message":"self-test passed：坏样本被拒 / 真实仓库预检通过"}\n'
        exit 0
        ;;
    '')
        if ! precheck "$ROOT"; then
            printf '[remedy] 预检未通过：%s\n' "$FAILMSG" >&2
            exit 1
        fi
        exec "$TARGET"
        ;;
    *)
        printf '[remedy] 未知参数：%s（用 --help 看用法）\n' "$1" >&2
        exit 2
        ;;
esac
