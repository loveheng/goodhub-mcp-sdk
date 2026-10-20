#!/bin/bash
# dist — 打包离线分发件（本地 Maven 仓目录 + zip）
#
# 产出：
#   dist/maven-repo/                        标准 Maven 目录布局（可直接当 file 仓消费）
#   dist/goodhub-mcp-sdk-<version>.zip      同一份内容的压缩包（可直接发给他人/内网）
#
# 用途：GitHub Packages 需要 token 才能拉；本分发件**免认证**，拷到任意机器即可用：
#   repositories { maven { url = uri("<解压目录>/maven-repo") } }
# 或整目录拷进 ~/.m2/repository（标准路径布局，Gradle 自动识别）。
#
# toolbox-script
# format: v1
# name: dist
# summary: 打包离线分发件——把 SDK 发布到本地 Maven 仓目录并打成 zip，免认证即可消费
# trigger: manual
# platform: unix
# alias: dist
#
# 注意：dist/ 是构建产物目录，不入 git（已在 .gitignore 中）。

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

VERSION="$(sed -n 's/^version=//p' "$ROOT/VERSION" 2>/dev/null | head -1)"
[ -n "$VERSION" ] || { echo "ERROR: 读不到 VERSION 文件的 version 字段" >&2; exit 2; }

echo "==> 发布到 $ROOT/dist/maven-repo（版本 $VERSION）"
if ! (cd "$ROOT" && ./gradlew --console=plain \
      :contract:publishReleasePublicationToDistRepository \
      :plugin-core:publishReleasePublicationToDistRepository); then
  echo "[remedy] 看上方 gradle 输出；确认 VERSION 文件可读、dist 目录可写" >&2
  exit 1
fi

AAR="$ROOT/dist/maven-repo/com/zzh/goodhub/mcp/goodhub-mcp-contract/$VERSION/goodhub-mcp-contract-$VERSION.aar"
[ -f "$AAR" ] || { echo "ERROR: 契约产物缺失：$AAR" >&2; exit 1; }

ZIP="$ROOT/dist/goodhub-mcp-sdk-$VERSION.zip"
rm -f "$ZIP"
(cd "$ROOT/dist" && zip -qr "$ZIP" maven-repo) || {
  echo "[remedy] 缺 zip 命令？可改用：tar -czf dist/goodhub-mcp-sdk-$VERSION.tar.gz -C dist maven-repo" >&2
  exit 1
}

echo "OK: 离线分发件就绪"
echo "    目录: $ROOT/dist/maven-repo"
echo "    压缩: $ZIP"
