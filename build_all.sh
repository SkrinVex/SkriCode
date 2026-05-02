#!/bin/bash
set -e

echo "🔨 Сборка runtime APK..."
./gradlew :app-runtime:assembleRelease

echo "📦 Копирование runtime в assets конструктора..."
mkdir -p app/src/main/assets
cp app-runtime/build/outputs/apk/release/app-runtime-release-unsigned.apk app/src/main/assets/runtime.apk

echo "🔨 Сборка конструктора с runtime внутри..."
./gradlew :app:assembleRelease

echo "✅ Готово!"
ls -lh app/build/outputs/apk/release/app-release.apk
