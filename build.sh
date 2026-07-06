#!/bin/bash
# ================================================================
#  AgentHub APK 构建脚本
#  用法: chmod +x build.sh && ./build.sh [debug|release]
# ================================================================
set -e

BUILD_TYPE="${1:-debug}"
echo "🔨 AgentHub 构建 — 模式: $BUILD_TYPE"
echo ""

# ---- 检查环境 ----
check_env() {
  local missing=0
  if ! command -v java &>/dev/null; then
    echo "❌ 未找到 Java。请安装 JDK 17+:"
    echo "   macOS:  brew install openjdk@17"
    echo "   Ubuntu: sudo apt install openjdk-17-jdk"
    echo "   Win:    https://adoptium.net/"
    missing=1
  fi
  if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "❌ 未设置 ANDROID_HOME / ANDROID_SDK_ROOT"
    echo "   请安装 Android Studio 或 SDK，然后设置环境变量"
    echo "   export ANDROID_HOME=\$HOME/Library/Android/sdk  (macOS)"
    echo "   export ANDROID_HOME=\$HOME/Android/Sdk          (Linux)"
    missing=1
  fi
  if [ $missing -eq 1 ]; then
    echo ""
    echo "💡 也可以用 Android Studio 直接打开 android/ 目录构建"
    exit 1
  fi
  echo "✅ Java: $(java -version 2>&1 | head -1)"
  echo "✅ Android SDK: $ANDROID_HOME"
  echo ""
}

# ---- 安装依赖 ----
install_deps() {
  if [ ! -d "node_modules" ]; then
    echo "📦 安装 npm 依赖..."
    npm install
    echo ""
  fi
}

# ---- Capacitor Sync ----
cap_sync() {
  echo "🔄 同步 Web 资源到 www/..."
  # 解引用 www/ 中的符号链接为实体文件（Capacitor 不处理 symlink）
  for f in www/*; do
    if [ -L "$f" ]; then
      echo "  解引用: $f"
      cp -rL "$f" "$f.tmp" && rm "$f" && mv "$f.tmp" "$f"
    fi
  done
  echo "🔄 Capacitor sync..."
  npx cap sync android
  # 验证无残留符号链接
  if find android/app/src/main/assets/public -type l | grep -q .; then
    echo "⚠️  警告: assets 中存在符号链接"; find android/app/src/main/assets/public -type l
  fi
  echo ""
}

# ---- 生成签名密钥（仅 release） ----
gen_keystore() {
  if [ "$BUILD_TYPE" = "release" ] && [ ! -f "android/agenthub.keystore" ]; then
    echo "🔑 生成签名密钥..."
    keytool -genkeypair -v \
      -keystore android/agenthub.keystore \
      -alias agenthub \
      -keyalg RSA -keysize 2048 \
      -validity 10000 \
      -storepass agenthub2026 \
      -keypass agenthub2026 \
      -dname "CN=AgentHub, OU=Dev, O=AgentHub, L=Beijing, ST=Beijing, C=CN"
    echo "✅ 签名密钥已生成"
    echo ""
  fi
}

# ---- 构建 APK ----
build_apk() {
  cd android
  if [ "$BUILD_TYPE" = "release" ]; then
    echo "🏗️ 构建 Release APK..."
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
  else
    echo "🏗️ 构建 Debug APK..."
    ./gradlew assembleDebug
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
  fi
  cd ..

  if [ -f "android/$APK_PATH" ]; then
    local size=$(du -h "android/$APK_PATH" | cut -f1)
    echo ""
    echo "✅ 构建成功！"
    echo "📱 APK: android/$APK_PATH ($size)"
    echo ""
    echo "安装到手机:"
    echo "  adb install android/$APK_PATH"
  else
    echo "❌ 构建失败，未找到 APK"
    exit 1
  fi
}

# ---- 主流程 ----
check_env
install_deps
gen_keystore
cap_sync
build_apk
