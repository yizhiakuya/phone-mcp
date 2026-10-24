#!/usr/bin/env bash
# 构建 APK，自动递增版本号，生成版本清单，并上传到中继主机。
#
# 用法：
#   bash scripts/build_apk.sh                 # 构建 + 递增版本 + 上传
#   bash scripts/build_apk.sh --no-bump       # 构建，不递增版本
#   bash scripts/build_apk.sh --no-upload     # 构建，不上传
#   bash scripts/build_apk.sh --changelog "修复截图超时"
#   bash scripts/build_apk.sh --notes release-notes.md   # 用自定义 release notes
#
# 上传需要设置环境变量（无内置默认值，避免把个人主机信息带进仓库）：
#   PHONE_MCP_RELAY_HOST   形如 user@host
#   PHONE_MCP_WEBROOT      服务器上的静态目录，默认 /var/www/phone-album
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/android/app/build.gradle.kts"
DIST="$ROOT/dist"
HOST="${PHONE_MCP_RELAY_HOST:-}"
WEBROOT="${PHONE_MCP_WEBROOT:-/var/www/phone-album}"
REMOTE_VER="$WEBROOT/version.json"

BUMP=1
UPLOAD=1
CHANGELOG=""
NOTES_FILE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --no-bump) BUMP=0; shift ;;
        --no-upload) UPLOAD=0; shift ;;
        --changelog) CHANGELOG="$2"; shift 2 ;;
        --notes) NOTES_FILE="$2"; shift 2 ;;
        *) echo "未知参数: $1" >&2; exit 1 ;;
    esac
done

# ---- 版本号 ----
CUR_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$GRADLE_FILE" | head -1)
CUR_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)

if [ "$BUMP" -eq 1 ]; then
    NEW_CODE=$((CUR_CODE + 1))
    # 1.0.0 -> 1.0.1，以此类推
    MAJOR=$(echo "$CUR_NAME" | cut -d. -f1)
    MINOR=$(echo "$CUR_NAME" | cut -d. -f2)
    PATCH=$(echo "$CUR_NAME" | cut -d. -f3)
    NEW_NAME="$MAJOR.$MINOR.$((PATCH + 1))"

    sed -i "s/versionCode = $CUR_CODE/versionCode = $NEW_CODE/" "$GRADLE_FILE"
    sed -i "s/versionName = \"$CUR_NAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE_FILE"
    echo "版本: $CUR_NAME ($CUR_CODE) -> $NEW_NAME ($NEW_CODE)"
else
    NEW_CODE=$CUR_CODE
    NEW_NAME=$CUR_NAME
    echo "版本: $CUR_NAME ($CUR_CODE)（不递增）"
fi

# ---- 构建 ----
cd "$ROOT/android"
if [ ! -f local.properties ]; then
    printf 'sdk.dir=%s\n' "${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}" > local.properties
fi

GRADLE="${GRADLE_BIN:-}"
if [ -z "$GRADLE" ]; then
    GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle.bat 2>/dev/null | head -1)
fi
if [ -z "$GRADLE" ]; then
    echo "找不到 gradle，设置 GRADLE_BIN 环境变量" >&2
    exit 1
fi

export JAVA_HOME="${JAVA_HOME:-D:\\tools\\jdk-21}"

# 自更新仓库（owner/repo）。不设置则打包出的 APK 不启用「检查更新」。
# 默认取 git remote，所以 clone 下来的人开箱即用。
GRADLE_ARGS="-q assembleRelease"
UPDATE_REPO="${PHONE_MCP_UPDATE_REPO:-}"
if [ -z "$UPDATE_REPO" ]; then
    UPDATE_REPO=$(git -C "$ROOT" remote get-url origin 2>/dev/null \
        | sed 's|.*github\.com[:/]||; s|\.git$||' || true)
fi
if [ -n "$UPDATE_REPO" ]; then
    GRADLE_ARGS="$GRADLE_ARGS -PupdateRepo=$UPDATE_REPO"
    echo "自更新源: GitHub Releases · $UPDATE_REPO"
else
    echo "自更新源: 未配置（App 内「检查更新」将提示未配置）"
fi

# 默认用 Gradle daemon。
#
# `--no-daemon` 每次都要重新启动 JVM 并重新加载整个构建模型，实测 41 秒；
# daemon 常驻后同样的构建 8 秒。改代码后的增量构建差距更明显。
#
# daemon 会常驻约 1-2GB 内存。设 PHONE_MCP_NO_DAEMON=1 可退回一次性构建
# （例如在 CI 或内存紧张的机器上）。
if [ "${PHONE_MCP_NO_DAEMON:-0}" = "1" ]; then
    GRADLE_ARGS="$GRADLE_ARGS --no-daemon"
fi

cmd //c "set JAVA_HOME=${JAVA_HOME}&& $(cygpath -w "$GRADLE") $GRADLE_ARGS"

mkdir -p "$DIST"
cp app/build/outputs/apk/release/app-release.apk "$DIST/phone-mcp.apk"

# 确认签名正确（防止悄悄回落到 debug 签名）
APKSIGNER="${APKSIGNER:-$HOME/AppData/Local/Android/Sdk/build-tools/34.0.0/apksigner.bat}"
if [ -f "$APKSIGNER" ]; then
    CERT=$("$APKSIGNER" verify --print-certs "$DIST/phone-mcp.apk" 2>/dev/null | grep "certificate DN" | head -1)
    echo "签名: $CERT"
    case "$CERT" in
        *"CN=Phone MCP"*) ;;
        *) echo "✗ 签名不是 release keystore，中止" >&2; exit 1 ;;
    esac
fi

MD5=$(md5sum "$DIST/phone-mcp.apk" | cut -d' ' -f1)
SIZE=$(stat -c%s "$DIST/phone-mcp.apk")
echo "APK: dist/phone-mcp.apk  ${SIZE} bytes  md5=$MD5"

# ---- 生成 version.json ----
cat > "$DIST/version.json" <<EOF
{
  "versionCode": $NEW_CODE,
  "versionName": "$NEW_NAME",
  "minSdk": 30,
  "apk": "/apk/phonemcp-latest.apk",
  "sizeBytes": $SIZE,
  "md5": "$MD5",
  "changelog": "${CHANGELOG:-版本 $NEW_NAME}",
  "releasedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
echo "清单: dist/version.json"

# ---- 上传 ----
if [ "$UPLOAD" -eq 1 ]; then
    if [ -z "$HOST" ]; then
        echo "未设置 PHONE_MCP_RELAY_HOST（形如 user@host），跳过上传。" >&2
        echo "本地产物在 $DIST/，或用 --no-upload 显式跳过。" >&2
        exit 1
    fi
    echo "上传到 $HOST …"
    # 按版本号命名，保留历史；latest 软链指向最新
    scp -o BatchMode=yes "$DIST/phone-mcp.apk" "$HOST:$WEBROOT/phonemcp-$NEW_NAME.apk"
    scp -o BatchMode=yes "$DIST/version.json" "$HOST:$REMOTE_VER"

    # 下载页随版本自动更新（MD5/大小/版本号），避免页面信息过期
    cat > "$DIST/download.html" <<HTML
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Phone MCP 下载</title>
<style>
:root{color-scheme:light dark}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f7f8fa;color:#16181d;
font:16px/1.6 -apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;padding:24px}
@media(prefers-color-scheme:dark){body{background:#0f1115;color:#e8eaed}
.card{background:#171a21!important;border-color:#252a33!important}.meta{background:#10131a!important;border-color:#252a33!important}}
.card{width:100%;max-width:480px;background:#fff;border:1px solid #e3e6eb;border-radius:16px;padding:28px}
h1{font-size:21px;margin:0 0 6px}.sub{color:#5b6472;font-size:14px;margin:0 0 22px}
a.btn{display:block;text-align:center;text-decoration:none;background:#2f6feb;color:#fff;
font-weight:600;padding:15px;border-radius:11px}
.meta{margin-top:18px;padding:14px;background:#f7f8fa;border:1px solid #e3e6eb;border-radius:10px;
font:12.5px/1.7 ui-monospace,Consolas,monospace;word-break:break-all}
.meta b{color:#5b6472}ol{padding-left:20px;margin:20px 0 0;font-size:14px}li{margin-bottom:9px}
</style></head><body><div class="card">
<h1>Phone MCP</h1>
<p class="sub">Android 11+ · 无障碍树 + 截图控制手机</p>
<a class="btn" href="/apk/phonemcp-latest.apk">下载 APK</a>
<div class="meta"><b>版本</b> $NEW_NAME ($NEW_CODE)<br>
<b>大小</b> $(awk "BEGIN{printf \"%.1f\", $SIZE/1048576}") MB<br>
<b>MD5</b> $MD5</div>
<ol>
<li>安装后打开 App</li>
<li>点「扫码配对」，扫电脑上生成的二维码</li>
<li>开启无障碍服务、允许后台运行</li>
<li>确认中继隧道显示「已连接」</li>
</ol></div></body></html>
HTML
    scp -o BatchMode=yes "$DIST/download.html" "$HOST:$WEBROOT/index-phonemcp.html"

    ssh -o BatchMode=yes "$HOST" "cd $WEBROOT && chmod 644 phonemcp-$NEW_NAME.apk version.json index-phonemcp.html && ln -sfn phonemcp-$NEW_NAME.apk phonemcp-latest.apk"
    echo "已上传。"
    echo "  下载页: ${PHONE_MCP_UPDATE_BASE_URL:-https://relay.example.com:8900}/phonemcp"
    echo "  直链:   ${PHONE_MCP_UPDATE_BASE_URL:-https://relay.example.com:8900}/apk/phonemcp-latest.apk"
    echo "  App 内「检查更新」也会收到 $NEW_NAME"
else
    echo "跳过上传（--no-upload）"
fi

# ---- 发布到 GitHub Releases ----
# 只有真正发版（递增了版本号 + 上传了 APK）才建 release。
# 用 --no-bump 或 --no-upload 的本地构建不发布，避免刷出一堆同版本 release。
if [ "$UPLOAD" -eq 1 ] && [ "$BUMP" -eq 1 ] && command -v gh >/dev/null 2>&1; then
    if gh repo view "$(git -C "$ROOT" remote get-url origin 2>/dev/null | sed 's|.*github.com[:/]||; s|\.git$||')" >/dev/null 2>&1; then
        REPO=$(git -C "$ROOT" remote get-url origin | sed 's|.*github.com[:/]||; s|\.git$||')
        TAG="v$NEW_NAME"
        echo "发布到 GitHub Releases：$REPO $TAG …"
        cp "$DIST/phone-mcp.apk" "$DIST/phone-mcp-$NEW_NAME.apk"
        # release notes：每次发版都重新生成，不复用上一版的文件。
        #
        # 之前的设计是"有 release-notes.md 就用它"，结果 1.1.19 复用了 1.1.18
        # 的笔记，标题和下载链接都指向旧版本。要写详细 notes 就用 --notes 指定，
        # 否则用 changelog 生成——保证内容永远和版本对得上。
        NOTES="$DIST/release-notes-$NEW_NAME.md"
        if [ -n "${NOTES_FILE:-}" ] && [ -f "$NOTES_FILE" ]; then
            cp "$NOTES_FILE" "$NOTES"
            echo "使用指定 notes: $NOTES_FILE"
        else
            {
                printf '## phone-mcp %s\n\n' "$NEW_NAME"
                printf '%s\n' "${CHANGELOG:-版本 $NEW_NAME}"
                printf '\n### 安装\n\n'
                printf '下载 `phone-mcp-%s.apk` 直接安装。首次使用需开启无障碍服务、' "$NEW_NAME"
                printf '授予悬浮窗权限；部分国产 ROM 还需允许「自启动」「后台无限制」'
                printf '「后台弹出界面」。\n\n'
                printf '**仅支持 arm64 设备、Android 11 及以上。** 签名须与旧版一致才能覆盖安装。\n'
                printf '\n### 更新方式\n\n'
                printf 'App 内「检查更新」直接从本仓库的 Releases 获取，无需其他更新源。\n'
            } > "$NOTES"
        fi
        # 机器可读的元数据统一放在末尾的代码块里。
        #
        # 为什么不散落在正文：changelog 里可能顺口提到版本号（例如
        # "之前把 1.1.19 换算成 10119"），正则扫正文会抓到错的那个。
        # 放在固定的尾部代码块里，App 只解析这一段就不会误读。
        #
        # 格式约定（App 端 Updater 按此解析）：
        #   ```meta
        #   versionCode: 51
        #   size: 23847802
        #   md5: 9fd3d5...
        #   ```
        # 注意：Gradle 构建产物不含本注释，App 端只认 ```meta 块。
        {
            printf '\n```meta\n'
            printf 'versionCode: %s\n' "$NEW_CODE"
            printf 'versionName: %s\n' "$NEW_NAME"
            printf 'size: %s\n' "$SIZE"
            printf 'md5: %s\n' "$MD5"
            printf '```\n'
        } >> "$NOTES"
        git -C "$ROOT" tag -a "$TAG" -m "phone-mcp $NEW_NAME" 2>/dev/null || true
        git -C "$ROOT" push origin "$TAG" -q 2>/dev/null || true
        gh release create "$TAG" "$DIST/phone-mcp-$NEW_NAME.apk" \
            --repo "$REPO" --title "phone-mcp $NEW_NAME" --notes-file "$NOTES" \
            || echo "⚠ release 创建失败（可能已存在），APK 仍可从下载页获取"
    else
        echo "跳过 GitHub Release（未配置 origin 或 gh 未登录）"
    fi
fi
