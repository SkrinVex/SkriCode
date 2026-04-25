#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ID="su.SkrinVex.SkriPts"

echo "Выбери тип сборки:"
echo "  1) debug"
echo "  2) release"
read -rp "Введи 1 или 2 [1]: " choice

case "${choice:-1}" in
  2) BUILD_TYPE="release"; VARIANT="Release" ;;
  *) BUILD_TYPE="debug";   VARIANT="Debug"   ;;
esac

APK="$PROJECT_DIR/app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"

echo ""
echo "→ Сборка ($BUILD_TYPE)..."
"$PROJECT_DIR/gradlew" "assemble$Variant" --quiet

echo "→ Проверка ADB..."
if ! adb devices | grep -q "device$"; then
  echo "Ошибка: нет подключённых устройств. Подключи телефон и включи USB-отладку."
  exit 1
fi

echo "→ Установка APK..."
adb install -r "$APK"

echo "→ Запуск приложения..."
adb shell am start -n "$APP_ID/.MainActivity"

echo ""
echo "✓ Готово!"
