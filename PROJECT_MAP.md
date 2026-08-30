# SkriCode — Архитектурная карта проекта

Данный файл содержит полное описание архитектуры, структуры модулей, ключевых компонентов, потоков данных и правил разработки для ИИ-агентов и разработчиков.

---

## 1. Обзор проекта

**SkriCode** — это мобильная среда визуального блочного программирования (IDE) и игровой 2D-движок для Android.
Позволяет пользователю создавать 2D-игры и интерактивные приложения на мобильном устройстве с помощью визуальных блоков (в духе Scratch / Pocket Code, но с расширенной физикой, системой частиц, покадровой анимацией спрайтов, продвинутыми выражениями и возможностью экспорта в полноценный автономный `.apk`).

### Стек технологий:
- **Язык**: Kotlin 2.0+
- **UI фреймворк**: Jetpack Compose (Material 3)
- **Архитектура**: MVVM, MVI (StateFlow / immutable data classes)
- **Сборка**: Gradle (Kotlin DSL), Android Gradle Plugin 8.9+
- **Целевые платформы**: Android 7.0+ (API 24..35)

---

## 2. Структура модулей

```text
SkriCode/
├── core-engine/        # Ядро движка (физика, компилятор блоков, симуляция, частицы, выражения)
├── app/                # Основное приложение-IDE (редактор блоков, сцен, хитбоксов, сборщик APK)
└── app-runtime/        # Автономный шаблон-рантайм (собирается в runtime.apk для экспорта проектов)
```

### 2.1 `:core-engine` (Библиотека ядра)
Не зависит от IDE. Содержит чистую логику симуляции, компиляции и рендеринга:
- `su.SkrinVex.SkriCode.engine.EngineModels`: Все data-классы состояния симуляции (`SimState`, `SimObject`, `SimCamera`, `JoystickState`, `PhysicsBody`, `Hitbox`, `Particle`, `ParticleEmitterState` и др.).
- `su.SkrinVex.SkriCode.block.BuiltinBlocks`: Реестр определений всех блоков (`BlockDef`), параметров (`BlockParam`), категорий (`BlockCategory`) и их визуального представления.
- `su.SkrinVex.SkriCode.block.BlockRegistry`: Фабрика блоков по их строковому типу `type`.
- `su.SkrinVex.SkriCode.engine.compiler.BlockCompiler`: Компилятор AST блоков (`BlockDef`) в плоский список байткод-инструкций `CompiledBlock` с вычислением прыжков циклов и условий.
- `su.SkrinVex.SkriCode.engine.SimEngine`: Главный интерпретатор симуляции. Обрабатывает запуск скриптов, события (ON_START, ON_TAP, ON_HOLD, ON_COLLISION), задержки (`delay`), слияние состояний (`buildMergedState`), слежение камеры (`tickCamera`) и управление джойстиком (`tickJoysticks`).
- `su.SkrinVex.SkriCode.engine.PhysicsWorld`: Физический движок (SAT-коллизии для выпуклых полигонов и кругов, гравитация, упругость, импульсы, фильтрация игнорируемых коллизий `collisionIgnore` с поддержкой `#тег`).
- `su.SkrinVex.SkriCode.engine.ParticleSystem`: Движок частиц, эмиттеров, покадровой спрайтовой анимации, тряски экрана (`ScreenShake`) и вспышек (`ScreenFlash`).
- `su.SkrinVex.SkriCode.engine.ExprEval`: Парсер и вычислитель математических и логических выражений. Поддерживает переменные `{var}`, таблицы `{tbl.key}`, константы экрана (`$screenLeft`, `$screenTop` и т.д.), функции, строки и числа.
- `su.SkrinVex.SkriCode.ui.sim.SimulationScreen`: Compose Canvas компонент для отрисовки состояния `SimState` на экране.

### 2.2 `:app` (IDE SkriCode)
- `su.SkrinVex.SkriCode.ui.home.HomeScreen`: Главный экран со списком проектов, справочником блоков, импортом/экспортом архивов проектов `.skri`.
- `su.SkrinVex.SkriCode.ui.editor.EditorScreen`: Главный визуальный редактор блоков активного скрипта:
  - Сворачивание / разворачивание блоков (`_collapsedBlocks`).
  - Парные блоки (`if_open`/`if_close`, `for_loop_open`/`for_loop_close`, `wait_open`/`wait_close`).
  - Централизованное удаление блоков по `blockId` (включая связанные парные блоки).
  - Выбор переменных, тегов, объектов, цветов, выражений.
- `su.SkrinVex.SkriCode.ui.editor.EditorViewModel`: Управление состоянием редактора (`EditorState`), сохранение в `ProjectRepository`, запуск превью симуляции с 60 FPS физическим циклом.
- `su.SkrinVex.SkriCode.ui.editor.LocationEditorScreen`: Визуальный редактор расстановки объектов на карте сцены.
- `su.SkrinVex.SkriCode.ui.editor.PositionPickerScreen`: Полноэкранный пикер координат (X, Y).
- `su.SkrinVex.SkriCode.ui.editor.HitboxEditorScreen`: Интерактивный редактор полигональных хитбоксов для объектов.
- `su.SkrinVex.SkriCode.ui.editor.SpriteAnimationEditorScreen`: Редактор нарезки и предпросмотра спрайт-листов.
- `su.SkrinVex.SkriCode.ui.expr.ExpressionEditorScreen`: Полноэкранный редактор формул и выражений.
- `su.SkrinVex.SkriCode.export.ExportManager`: Утилита сборки автономного APK из шаблона `runtime.apk` с внедрением `project.json` и переподписью APK.

### 2.3 `:app-runtime` (Standalone APK Runner)
- Легковесный шаблон приложения, собирающийся в `assets/runtime.apk`.
- Читает `assets/project.json`, загружает спрайты/звуки и воспроизводит игру через `SimEngine` без элементов IDE.

---

## 3. Модели данных проекта

### Иерархия проекта (`ScriptProject`):
```text
ScriptProject
├── Scene (Сцены: activeSceneId, scripts, locationBlocks)
│   ├── Script (Скрипты: event, eventTarget, blocks)
│   │   └── BlockDef (Блоки: id, type, params, children, pairId)
│   └── LocationObject (Объекты сцены)
├── ProjectVar (Глобальные переменные: name, value, scope)
├── ProjectTag (Теги: name, color, scope)
├── ProjectTable (Таблицы ключ-значение)
├── SpriteAsset (Загруженные спрайты / изображения)
└── SoundAsset (Загруженные аудиофайлы)
```

### Модели симуляции (`SimState`):
- `objects: Map<String, SimObject>`: Все живые объекты на сцене (фигуры, спрайты, текст, поля ввода, кнопки).
- `joysticks: Map<String, JoystickState>`: Виртуальные экранные джойстики.
- `camera: SimCamera?`: Камера слежения за объектом или `#тегом` с плавным сглаживанием зума и координат.
- `globalVars: Map<String, String>`: Живые переменные симуляции.
- `tables: Map<String, Map<String, String>>`: Живые таблицы.
- `particles: List<Particle>`, `particleEmitters: Map<String, ParticleEmitterState>`: Системы частиц.
- `screenShake: ScreenShakeState`, `screenFlash: ScreenFlashState`: Экранные эффекты.

---

## 4. Жизненный цикл симуляции и физический цикл (60 FPS)

В `EditorViewModel` (превью в IDE) и `RuntimeViewModel` (автономный APK) работает фоновый корутин-цикл 60 FPS:

```text
[16 ms tick]
   │
   ├── 1. SimEngine.tickJoysticks(sim)
   │      - Считывает наклон джойстиков
   │      - Находит целевые объекты по имени или по #тегу (SimEngine.getMatchingObjects)
   │      - Применяет смещение, скорость, поворот или физическую скорость
   │
   ├── 2. SimEngine.physicsTick(sim)
   │      - PhysicsWorld.tick: гравитация, скорости, SAT коллизии, упругость
   │      - ParticleSystem.tickAnimations: покадровая анимация спрайт-листов
   │      - ParticleSystem.tick: эмиттеры и физика частиц
   │      - ParticleSystem.tickShake / tickFlash: затухание тряски и вспышек
   │
   ├── 3. SimEngine.tickCamera(sim)
   │      - Находит целевой объект(ы) камеры по имени или #тегу
   │      - Плавно интерполирует зум (targetZoom -> zoom)
   │      - Плавно интерполирует позицию (targetOffX/Y с учетом границ boundMin/Max)
   │
   └── 4. Запуск событийных скриптов:
          - ON_HOLD (удержание)
          - ON_COLLISION / ON_COLLISION_END (столкновения)
```

---

## 5. Правила работы с состоянием симуляции (State Merging)

> [!IMPORTANT]
> **Критическое правило для SimEngine**:
> Во время выполнения скриптов с задержками (`WaitDelay`, `WaitLoopStart`) симуляция продолжает тикать с частотой 60 FPS.
> Чтобы изменения из скрипта не перезаписывали живые координаты объектов и камеры:
> 1. Скрипт фиксирует измененные поля объектов в `modifiedFields[name]`. При слиянии (`buildMergedState`) обновляются **только** измененные поля, а живые физические координаты `x, y, vx, vy` берутся из актуального `base` состояния.
> 2. Для камеры используется `cameraModifiedRef`: если скрипт менял параметры камеры (`zoom`, `targetZoom`, `targetName`, `bounds`), обновляются конфигурационные поля камеры, сохраняя живые смещения `offsetX, offsetY` из цикла слежения.

---

## 6. Теги (`#tag`) и адресация объектов

Любой блок, принимающий объект управления/цели (`target`, `name`, `ignore`, `unignore`):
- Поддерживает **прямое имя**: `"player"`, `"box1"`.
- Поддерживает **тег**: `"#player"`, `"#enemy"`. Если указан тег, действие применяется ко **всем** объектам с этим тегом.
- Поддерживает **списки через запятую**: `"player, #minions, box1"`.
- Поддерживает ключевое слово **все**: `"all"` или `"*"` (все объекты сцены).
- Для сопоставления всегда используется универсальный метод:
  `SimEngine.getMatchingObjects(targetPattern, objects)`.

---

## 7. Команды сборки и проверки

Все команды выполняются из корня проекта:

```bash
# 1. Прогон всех тестов движка и IDE:
./gradlew test

# 2. Сборка рантайма и копирование в assets IDE:
./gradlew :app-runtime:copyRuntimeApk

# 3. Полная сборка отладочного APK IDE:
./gradlew :app-runtime:copyRuntimeApk :app:assembleDebug

# 4. Прогон конкретного теста SimEngine:
./gradlew :core-engine:testDebugUnitTest --tests "su.SkrinVex.SkriCode.engine.SimEngineTest"
```

> [!TIP]
> При изменении `:core-engine` или `:app-runtime` перед сборкой `:app:assembleDebug` **всегда** запускайте задачу `:app-runtime:copyRuntimeApk`, чтобы в assets IDE попал свежий рантайм.

---

## 8. Памятка по разработке и частым ошибкам

1. **Удаление блоков**:
   - Никогда не используйте позиционный `index` в колбэках удаления, так как список может перестроиться. Всегда вызывайте `removeBlock(block.id)` по ID блока.
2. **Таймеры и задержки**:
   - Блок `wait_open` с `count == "1"` компилируется в `CompiledBlock.WaitDelay`.
   - Блок `wait_open` с `count > 1` (или циклы) компилируется в `CompiledBlock.WaitLoopStart` / `WaitLoopEnd`.
3. **Непрозрачность (`alpha`)**:
   - Хранится в `SimObject.alpha` (0.0..1.0) и `SimObject.spriteAlpha`.
   - В UI Compose SimulationScreen применяется через `Modifier.alpha(obj.alpha)`.
4. **Зум камеры**:
   - `camera_zoom` принимает `name` камеры, `zoom` (0.05..20) и `smoothing` (0.01..1.0). При `smoothing = 1.0` зум меняется мгновенно.
