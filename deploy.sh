#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ID="su.SkrinVex.SkriCode"

echo "Выбери действие:"
echo "  1) Сборка debug (app) + установка на устройство"
echo "  2) Сборка release (app) → app-release.apk"
echo "  3) Сборка runtime APK → app/src/main/assets/runtime.apk"
read -rp "Введи 1, 2 или 3 [1]: " choice

case "${choice:-1}" in
  2)
    echo ""
    echo "→ Сборка release..."
    "$PROJECT_DIR/gradlew" assembleRelease --quiet
    APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    cp "$APK" "$PROJECT_DIR/app-release.apk"
    echo "✓ Готово! APK: $PROJECT_DIR/app-release.apk"
    ;;
  3)
    echo ""
    echo "→ Сборка runtime APK..."
    "$PROJECT_DIR/gradlew" :app-runtime:copyRuntimeApk --quiet
    echo "✓ Готово! runtime.apk скопирован в app/src/main/assets/"
    ;;
  *)
    echo ""
    echo "→ Сборка debug..."
    "$PROJECT_DIR/gradlew" assembleDebug --quiet

    echo "→ Проверка ADB..."
    if ! adb devices | grep -q "device$"; then
      echo "Ошибка: нет подключённых устройств. Подключи телефон и включи USB-отладку."
      exit 1
    fi

    APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    echo "→ Установка APK..."
    adb install -r "$APK"

    echo "→ Запуск приложения..."
    adb shell am start -n "$APP_ID/.MainActivity"

    echo "✓ Готово!"
    ;;
esac
