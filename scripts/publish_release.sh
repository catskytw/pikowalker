#!/usr/bin/env bash
# 建立新版 debug APK 並發布到 GitHub Releases，供 App 內「檢查更新」使用。
# 用 debug build 是因為手機上目前安裝的也是 debug 簽章版本 —
# Android 更新安裝時新舊 APK 簽章必須一致，否則會安裝失敗。
set -e
cd "$(dirname "$0")/.."

GH="/c/Program Files/GitHub CLI/gh.exe"
REPO="catskytw/pikowalker"

# 用 clean 而非單純 assembleDebug —— 曾發生過增量編譯沒有正確重新編譯
# 參照到 BuildConfig 的類別，導致版本號等欄位停留在舊值的問題。
./gradlew clean assembleDebug

BUILD_NUMBER=$(grep buildNumber app/version.properties | cut -d= -f2)
VERSION="1.0.$BUILD_NUMBER"
APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="app/build/outputs/apk/debug/pikowalker-$VERSION.apk"

cp "$APK_SRC" "$APK_DEST"

NOTES="${1:-新版本 $VERSION}"

"$GH" release create "$VERSION" "$APK_DEST" \
    --repo "$REPO" \
    --title "v$VERSION" \
    --notes "$NOTES"

echo "已發布版本 $VERSION 到 $REPO"
