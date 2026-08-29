package su.SkrinVex.SkriCode.block

import java.util.UUID

private fun p(value: String, label: String, hint: String = "", type: ParamType = ParamType.TEXT) =
    BlockParam(value, label, hint, type)

private fun build(
    type: String,
    displayName: String,
    description: String,
    category: BlockCategory,
    params: List<Pair<String, BlockParam>>
) = BlockDef(
    id = UUID.randomUUID().toString(),
    type = type,
    displayName = displayName,
    description = description,
    category = category,
    params = params.toMap(),
    paramOrder = params.map { it.first }
)

object BlockFactory {
    fun create(type: String): BlockDef? = when (type) {

        "set_var" -> build("set_var", "Переменная", "Создаёт или обновляет переменную", BlockCategory.VARIABLE, listOf(
            "name"  to p("", "Имя переменной", "Нажми чтобы выбрать"),
            "value" to p("0", "Значение", "Число или текст")))

        "set_tag" -> build("set_tag", "Установить тег", "Присваивает тег объекту для группировки", BlockCategory.SIMULATION, listOf(
            "object" to p("rect1", "Имя объекта", "Какому объекту"),
            "tag"    to p("", "Тег", "Нажми чтобы выбрать")))

        "sim_create" -> build("sim_create", "Создать объект", "Создаёт прямоугольник на сцене", BlockCategory.SIMULATION, listOf(
            "name"   to p("rect1", "Имя объекта", "Уникальное имя"),
            "x"      to p("0", "X (от центра)", "Вправо = +, влево = −", ParamType.NUMBER),
            "y"      to p("0", "Y (от центра)", "Вверх = +, вниз = −", ParamType.NUMBER),
            "width"  to p("100", "Ширина", "В пикселях", ParamType.NUMBER),
            "height" to p("60", "Высота", "В пикселях", ParamType.NUMBER),
            "radius" to p("8", "Скругление", "Радиус углов", ParamType.NUMBER),
            "color"  to p("#4F8EF7", "Цвет", "#RRGGBB")))

        "sim_move" -> build("sim_move", "Переместить объект", "Изменяет позицию объекта на сцене", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект"),
            "mode" to p("instant", "Режим", "instant = в позицию, step = шаг", ParamType.SELECT),
            "x"    to p("0", "X", "Позиция или шаг по X", ParamType.NUMBER),
            "y"    to p("0", "Y", "Позиция или шаг по Y", ParamType.NUMBER)))

        "sim_resize" -> build("sim_resize", "Изменить размер", "Меняет ширину и высоту объекта", BlockCategory.SIMULATION, listOf(
            "name"   to p("rect1", "Имя объекта", "Какой объект"),
            "width"  to p("100", "Ширина", "Новая ширина", ParamType.NUMBER),
            "height" to p("60", "Высота", "Новая высота", ParamType.NUMBER)))

        "sim_color" -> build("sim_color", "Изменить цвет", "Меняет цвет объекта", BlockCategory.SIMULATION, listOf(
            "name"  to p("rect1", "Имя объекта", "Какой объект"),
            "color" to p("#EF4444", "Цвет", "#RRGGBB")))

        "sim_bg_color" -> build("sim_bg_color", "Цвет фона", "Устанавливает цвет фона экрана/сцены", BlockCategory.SIMULATION, listOf(
            "color" to p("#0F172A", "Цвет фона", "Цвет фона сцены (#RRGGBB)")))

        "sim_update_text" -> build("sim_update_text", "Обновить текст", "Меняет текст любого объекта (sim_text или sim_create)", BlockCategory.SIMULATION, listOf(
            "name" to p("text1", "Имя объекта", "Любой объект с текстом"),
            "text" to p("", "Новый текст", "Поддерживает {переменные} и перенос строк (\\n)")))

        "sim_text" -> build("sim_text", "Текстовый объект", "Создаёт текст на сцене", BlockCategory.SIMULATION, listOf(
            "name"      to p("text1", "Имя объекта", "Уникальное имя"),
            "text"      to p("Привет!", "Текст", "Поддерживает {переменные} и перенос строк (\\n)"),
            "x"         to p("0", "X", "Позиция X", ParamType.NUMBER),
            "y"         to p("0", "Y", "Позиция Y", ParamType.NUMBER),
            "width"     to p("200", "Ширина", "Ширина области", ParamType.NUMBER),
            "height"    to p("40", "Высота", "Высота области", ParamType.NUMBER),
            "size"      to p("16", "Размер шрифта", "В пикселях", ParamType.NUMBER),
            "bold"      to p("false", "Жирный", "true или false"),
            "textColor" to p("#FFFFFF", "Цвет текста", "#RRGGBB")))

        "sim_button" -> build("sim_button", "Кнопка", "Создаёт кнопку или плашку с текстом на сцене", BlockCategory.SIMULATION, listOf(
            "name"      to p("btn1", "Имя кнопки", "Уникальное имя"),
            "text"      to p("Кнопка", "Текст кнопки", "Поддерживает {переменные} и перенос строк (\\n)"),
            "x"         to p("0", "X (от центра)", "Вправо = +, влево = −", ParamType.NUMBER),
            "y"         to p("0", "Y (от центра)", "Вверх = +, вниз = −", ParamType.NUMBER),
            "width"     to p("160", "Ширина", "В пикселях", ParamType.NUMBER),
            "height"    to p("50", "Высота", "В пикселях", ParamType.NUMBER),
            "radius"    to p("8", "Скругление", "Радиус углов", ParamType.NUMBER),
            "color"     to p("#4F8EF7", "Цвет фона", "#RRGGBB"),
            "textColor" to p("#FFFFFF", "Цвет текста", "#RRGGBB"),
            "size"      to p("16", "Размер шрифта", "В пикселях", ParamType.NUMBER),
            "bold"      to p("false", "Жирный", "true или false")))

        "sim_text_input" -> build("sim_text_input", "Поле ввода текста", "Создаёт интерактивное поле текстового ввода", BlockCategory.WIDGET, listOf(
            "name"        to p("input1", "Имя поля", "Уникальное имя виджета"),
            "placeholder" to p("Введите текст...", "Подсказка", "Текст когда поле пустое"),
            "text"        to p("", "Начальный текст", "Начальное значение"),
            "var"         to p("user_text", "Переменная", "Куда сохранять введённый текст"),
            "multiline"   to p("false", "Многострочный", "true или false", ParamType.SELECT),
            "x"           to p("0", "X (от центра)", "Вправо = +, влево = −", ParamType.NUMBER),
            "y"           to p("0", "Y (от центра)", "Вверх = +, вниз = −", ParamType.NUMBER),
            "width"       to p("260", "Ширина", "В пикселях", ParamType.NUMBER),
            "height"      to p("52", "Высота", "В пикселях", ParamType.NUMBER),
            "radius"      to p("8", "Скругление", "Радиус углов", ParamType.NUMBER),
            "color"       to p("#1E293B", "Цвет фона", "#RRGGBB"),
            "textColor"   to p("#FFFFFF", "Цвет текста", "#RRGGBB"),
            "size"        to p("15", "Размер шрифта", "В пикселях", ParamType.NUMBER),
            "trigger"     to p("keyboard", "Триггер ввода", "keyboard = по клавиатуре, button = по нажатию на объект", ParamType.SELECT),
            "button"      to p("", "Имя объекта-кнопки", "Имя или #тег объекта для подтверждения")))

        "sim_clear_focus" -> build("sim_clear_focus", "Снять фокус с поля ввода", "Снимает фокус с активного поля ввода и скрывает клавиатуру", BlockCategory.WIDGET, emptyList())

        "if_block" -> build("if_block", "Условие (если)", "Выполняет блоки если условие истинно", BlockCategory.CONTROL, listOf(
            "left"  to p("", "Левое значение", "Переменная или число, напр. {score}"),
            "op"    to p("==", "Оператор", "== != > < >= <=", ParamType.SELECT),
            "right" to p("0", "Правое значение", "С чем сравниваем")))

        "sim_stop" -> build("sim_stop", "Завершить симуляцию", "Останавливает выполнение скрипта", BlockCategory.CONTROL, emptyList())

        // Циклы (legacy — только для обратной совместимости, не показываются в пикере)
        "for_loop" -> build("for_loop", "Цикл (повторить)", "Повторяет блоки указанное количество раз (0 или true = бесконечно)", BlockCategory.CONTROL, listOf(
            "count" to p("5", "Количество", "Сколько раз повторить (0 или true = бесконечно)", ParamType.NUMBER))).copy(
            children = mapOf("body" to emptyList()))

        "while_loop" -> build("while_loop", "Цикл (пока)", "Повторяет блоки пока условие истинно", BlockCategory.CONTROL, listOf(
            "left"  to p("", "Левое значение", "Переменная или число"),
            "op"    to p("<=", "Оператор", "== != > < >= <=", ParamType.SELECT),
            "right" to p("10", "Правое значение", "С чем сравниваем"))).copy(
            children = mapOf("body" to emptyList()))

        "wait" -> build("wait", "Таймер", "Повторяет вложенные блоки с заданным интервалом", BlockCategory.CONTROL, listOf(
            "seconds" to p("1", "Интервал (сек)", "Пауза между повторениями", ParamType.NUMBER),
            "count"   to p("1", "Повторений", "0 = бесконечно (пока зажата кнопка)", ParamType.NUMBER))).copy(
            children = mapOf("body" to emptyList()))

        // ── Open/Close блоки ──────────────────────────────────────────────────────
        "if_open" -> build("if_open", "Условие (если)", "Выполняет блоки до конца если условие истинно", BlockCategory.CONTROL, listOf(
            "left"  to p("", "Левое значение", "Переменная или число, напр. {score}"),
            "op"    to p("==", "Оператор", "== != > < >= <=", ParamType.SELECT),
            "right" to p("0", "Правое значение", "С чем сравниваем")))

        "else_block" -> build("else_block", "Иначе", "Иначе — блоки до конца условия", BlockCategory.CONTROL, emptyList())

        "if_close" -> build("if_close", "Конец условия", "Конец блока условия", BlockCategory.CONTROL, emptyList())

        "for_loop_open" -> build("for_loop_open", "Цикл (повторить)", "Повторяет блоки до конца цикла (0 или true = бесконечно)", BlockCategory.CONTROL, listOf(
            "count" to p("5", "Количество", "Сколько раз повторить (0 или true = бесконечно)", ParamType.NUMBER)))

        "for_loop_close" -> build("for_loop_close", "Конец цикла", "Конец цикла (повторить)", BlockCategory.CONTROL, emptyList())

        "while_loop_open" -> build("while_loop_open", "Цикл (пока)", "Повторяет блоки пока условие истинно", BlockCategory.CONTROL, listOf(
            "left"  to p("", "Левое значение", "Переменная или число"),
            "op"    to p("<=", "Оператор", "== != > < >= <=", ParamType.SELECT),
            "right" to p("10", "Правое значение", "С чем сравниваем")))

        "while_loop_close" -> build("while_loop_close", "Конец цикла (пока)", "Конец цикла (пока)", BlockCategory.CONTROL, emptyList())

        "wait_open" -> build("wait_open", "Таймер", "Выполняет блоки до конца таймера с интервалом", BlockCategory.CONTROL, listOf(
            "seconds" to p("1", "Интервал (сек)", "Пауза между повторениями", ParamType.NUMBER),
            "count"   to p("1", "Повторений", "0 = бесконечно", ParamType.NUMBER)))

        "wait_close" -> build("wait_close", "Конец таймера", "Конец таймера", BlockCategory.CONTROL, emptyList())

        // Видимость объектов
        "sim_hide" -> build("sim_hide", "Скрыть объект", "Делает объект невидимым и некликабельным", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект скрыть")))

        "sim_show" -> build("sim_show", "Показать объект", "Делает объект видимым и кликабельным", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект показать")))

        "sim_touch_disable" -> build("sim_touch_disable", "Запретить касание", "Блокирует нажатия и удержания на объекте", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Имя объекта или #тег")))

        "sim_touch_enable" -> build("sim_touch_enable", "Разрешить касание", "Разрешает нажатия и удержания на объекте", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Имя объекта или #тег")))

        "sim_rotate" -> build("sim_rotate", "Вращать объект", "Изменяет угол поворота объекта", BlockCategory.SIMULATION, listOf(
            "name"  to p("rect1", "Имя объекта", "Какой объект"),
            "mode"  to p("instant", "Режим", "instant = установить, step = добавить", ParamType.SELECT),
            "angle" to p("0", "Угол (°)", "Градусы по часовой стрелке", ParamType.NUMBER)))

        "sim_joystick" -> build("sim_joystick", "Джойстик", "Создаёт виртуальный джойстик для управления объектом", BlockCategory.SIMULATION, listOf(
            "name"        to p("joy1", "Имя джойстика", "Уникальное имя"),
            "x"           to p("-250", "X (от центра)", "Позиция X", ParamType.NUMBER),
            "y"           to p("\$screenBottom + 300", "Y (от центра)", "Позиция Y"),
            "baseRadius"  to p("100", "Радиус базы", "Размер основания", ParamType.NUMBER),
            "knobRadius"  to p("40", "Радиус ручки", "Размер ручки", ParamType.NUMBER),
            "baseColor"   to p("#334466", "Цвет базы", "#RRGGBB"),
            "knobColor"   to p("#4F8EF7", "Цвет ручки", "#RRGGBB"),
            "target"      to p("rect1", "Объект управления", "Какой объект двигать"),
            "speed"       to p("8", "Скорость", "Пикселей за тик", ParamType.NUMBER),
            "directional" to p("false", "Поворот по направлению", "true = объект поворачивается")))

        "sim_modify" -> build("sim_modify", "Изменить свойства", "Универсальное изменение свойств объекта", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект изменить"))).copy(
            children = mapOf("props" to emptyList()))

        "modify_prop" -> build("modify_prop", "Свойство", "Свойство для изменения", BlockCategory.SIMULATION, listOf(
            "prop" to p("", "Свойство", ""),
            "value" to p("", "Значение", "")))

        "sim_delete" -> build("sim_delete", "Удалить объект", "Удаляет объект или джойстик со сцены", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Имя, тег (#tag) или переменная")))

        "sim_physics" -> build("sim_physics", "Физика объекта", "Устанавливает физическое тело объекта", BlockCategory.PHYSICS, listOf(
            "name"       to p("rect1", "Имя объекта", "Какому объекту"),
            "gravity"    to p("-9.8", "Гравитация", "px/тик² (0 = нет гравитации)", ParamType.NUMBER),
            "static"     to p("false", "Статический", "true = нельзя двигать физикой"),
            "bounciness" to p("0", "Упругость", "0..1 (0 = нет отскока)", ParamType.NUMBER),
            "mass"       to p("1", "Масса", "Масса объекта", ParamType.NUMBER),
            "vx"         to p("0", "Скорость X", "Начальная скорость по X", ParamType.NUMBER),
            "vy"         to p("0", "Скорость Y", "Начальная скорость по Y", ParamType.NUMBER)))

        "physics_impulse" -> build("physics_impulse", "Импульс (прыжок)", "Мгновенно добавляет скорость физическому телу", BlockCategory.PHYSICS, listOf(
            "name" to p("rect1", "Имя объекта", "Какому объекту"),
            "vx"   to p("0", "Импульс X", "Добавить к скорости по X", ParamType.NUMBER),
            "vy"   to p("500", "Импульс Y", "Добавить к скорости по Y (+ = вверх)", ParamType.NUMBER)))

        "physics_move" -> build("physics_move", "Физическое движение", "Двигает объект через физику (скорость/поворот)", BlockCategory.PHYSICS, listOf(
            "name"     to p("rect1", "Имя объекта", "Какому объекту"),
            "speed"    to p("0", "Скорость вперёд", "По направлению объекта (+ = вперёд)", ParamType.NUMBER),
            "turn"     to p("0", "Поворот °/тик", "Угловая скорость (+ = по часовой)", ParamType.NUMBER),
            "friction" to p("0.9", "Трение", "0..1 (1 = нет трения, 0.9 = умеренное)", ParamType.NUMBER)))

        "sim_hitbox" -> build("sim_hitbox", "Хитбокс", "Настраивает хитбокс объекта", BlockCategory.PHYSICS, listOf(
            "name"   to p("rect1", "Имя объекта", "Какому объекту"),
            "type"   to p("auto", "Тип", "auto / manual"),
            "points" to p("", "Точки", "JSON точек (авто = пусто")))

        "sim_layer" -> build("sim_layer", "Слой объекта", "Устанавливает порядок отрисовки (меньше = дальше)", BlockCategory.SIMULATION, listOf(
            "name"  to p("rect1", "Имя объекта", "Какому объекту"),
            "layer" to p("0", "Слой", "0 = фон, 10 = передний план", ParamType.NUMBER)))

        "sim_no_collision" -> build("sim_no_collision", "Игнорировать коллизию", "Объект не будет сталкиваться с указанными объектами/тегами", BlockCategory.PHYSICS, listOf(
            "name"   to p("ammo", "Имя объекта", "Кому игнорировать"),
            "ignore" to p("player", "Игнорировать", "Имя или #тег через запятую")))

        "physics_toggle" -> build("physics_toggle", "Переключить физику", "Включает или выключает физику симуляции", BlockCategory.PHYSICS, listOf(
            "enabled" to p("true", "Физика", "true = вкл, false = выкл")))

        "sim_camera" -> build("sim_camera", "Создать камеру", "Создаёт камеру слежения за объектом", BlockCategory.CAMERA, listOf(
            "name"      to p("cam1", "Имя камеры", "Уникальное имя"),
            "target"    to p("", "Объект слежения", "Имя объекта или переменная"),
            "smoothing" to p("0.1", "Плавность", "0.01 = очень плавно, 1 = мгновенно", ParamType.NUMBER),
            "ui_tags"   to p("", "Теги интерфейса", "Теги через запятую: ui, hud"),
            "enabled"   to p("true", "Включена", "true = активна сразу")))

        "camera_toggle" -> build("camera_toggle", "Переключить камеру", "Включает или выключает камеру слежения", BlockCategory.CAMERA, listOf(
            "name"    to p("cam1", "Имя камеры", "Какую камеру"),
            "enabled" to p("true", "Включена", "true = вкл, false = выкл")))

        "table_set" -> build("table_set", "Таблица: записать", "Записывает значение по ключу в таблицу", BlockCategory.VARIABLE, listOf(
            "table" to p("", "Таблица", "Нажми чтобы выбрать"),
            "key"   to p("", "Ключ", "Строка или выражение"),
            "value" to p("", "Значение", "Что записать")))

        "table_get" -> build("table_get", "Таблица: читать", "Читает значение по ключу из таблицы в переменную", BlockCategory.VARIABLE, listOf(
            "table" to p("", "Таблица", "Нажми чтобы выбрать"),
            "key"   to p("", "Ключ", "Строка или выражение"),
            "var"   to p("", "Переменная", "Куда сохранить результат")))

        "save_var" -> build("save_var", "Сохранить переменную", "Сохраняет переменную в память устройства", BlockCategory.VARIABLE, listOf(
            "key"     to p("", "Ключ сохранения", "Уникальное имя для сохранения"),
            "value"   to p("", "Значение", "Переменная или выражение"),
            "encrypt" to p("false", "Шифровать", "true = зашифровать данные")))

        "load_var" -> build("load_var", "Загрузить переменную", "Загружает сохранённое значение в переменную", BlockCategory.VARIABLE, listOf(
            "key"     to p("", "Ключ сохранения", "Тот же ключ что при сохранении"),
            "var"     to p("", "Переменная", "Куда загрузить"),
            "default" to p("0", "Значение по умолчанию", "Если сохранения нет"),
            "encrypt" to p("false", "Шифровать", "true = данные были зашифрованы")))

        "save_table" -> build("save_table", "Сохранить таблицу", "Сохраняет всю таблицу в память устройства", BlockCategory.VARIABLE, listOf(
            "key"     to p("", "Ключ сохранения", "Уникальное имя для сохранения"),
            "table"   to p("", "Таблица", "Нажми чтобы выбрать"),
            "encrypt" to p("false", "Шифровать", "true = зашифровать данные")))

        "load_table" -> build("load_table", "Загрузить таблицу", "Загружает сохранённую таблицу", BlockCategory.VARIABLE, listOf(
            "key"     to p("", "Ключ сохранения", "Тот же ключ что при сохранении"),
            "table"   to p("", "Таблица", "Нажми чтобы выбрать"),
            "encrypt" to p("false", "Шифровать", "true = данные были зашифрованы")))

        "scene_switch" -> build("scene_switch", "Перейти на сцену", "Переключает активную сцену", BlockCategory.CONTROL, listOf(
            "scene" to p("", "Сцена", "Имя сцены для перехода")))

        // ── Спрайты ──────────────────────────────────────────────────────────────
        "set_texture" -> build("set_texture", "Установить текстуру", "Назначает спрайт на прямоугольник или джойстик", BlockCategory.SPRITE, listOf(
            "name"    to p("rect1", "Имя объекта", "Прямоугольник или джойстик"),
            "sprite"  to p("", "Спрайт", "Имя спрайта из проекта"),
            "scaleX"  to p("1.0", "Масштаб X", "1.0 = оригинал", ParamType.NUMBER),
            "scaleY"  to p("1.0", "Масштаб Y", "1.0 = оригинал", ParamType.NUMBER),
            "alpha"   to p("1.0", "Прозрачность", "0.0..1.0", ParamType.NUMBER),
            "cropX"   to p("0", "Обрезка X", "Левый край (px)", ParamType.NUMBER),
            "cropY"   to p("0", "Обрезка Y", "Верхний край (px)", ParamType.NUMBER),
            "cropW"   to p("0", "Ширина обрезки", "0 = вся ширина", ParamType.NUMBER),
            "cropH"   to p("0", "Высота обрезки", "0 = вся высота", ParamType.NUMBER)))

        "sim_sprite" -> build("sim_sprite", "Создать спрайт-объект", "Создаёт объект с текстурой и хитбоксом по размеру спрайта", BlockCategory.SPRITE, listOf(
            "name"   to p("sprite1", "Имя объекта", "Уникальное имя"),
            "sprite" to p("", "Спрайт", "Имя спрайта из проекта"),
            "x"      to p("0", "X (от центра)", "Вправо = +, влево = −", ParamType.NUMBER),
            "y"      to p("0", "Y (от центра)", "Вверх = +, вниз = −", ParamType.NUMBER),
            "width"  to p("0", "Ширина", "0 = по размеру спрайта", ParamType.NUMBER),
            "height" to p("0", "Высота", "0 = по размеру спрайта", ParamType.NUMBER),
            "alpha"  to p("1.0", "Прозрачность", "0.0..1.0", ParamType.NUMBER)))

        // ── Покадровая анимация ──────────────────────────────────────────────────
        "anim_play" -> build("anim_play", "Играть анимацию", "Запускает покадровую анимацию спрайт-листа", BlockCategory.SPRITE, listOf(
            "name"       to p("rect1", "Имя объекта", "Какой объект анимировать"),
            "sprite"     to p("", "Спрайт-лист", "Имя текстуры с сеткой кадров"),
            "cols"       to p("4", "Столбцов (кадров в ширину)", "Количество столбцов сетки", ParamType.NUMBER),
            "rows"       to p("1", "Строк (кадров в высоту)", "Количество строк сетки", ParamType.NUMBER),
            "startFrame" to p("0", "Начальный кадр", "Индекс от 0", ParamType.NUMBER),
            "endFrame"   to p("0", "Конечный кадр", "0 = до последнего кадра", ParamType.NUMBER),
            "fps"        to p("12", "Скорость (FPS)", "Кадров в секунду", ParamType.NUMBER),
            "loop"       to p("true", "Зациклить", "true = бесконечно, false = один раз", ParamType.SELECT),
            "offsetX"    to p("0", "Смещение X (px)", "Отступ сетки слева в пикселях", ParamType.NUMBER),
            "offsetY"    to p("0", "Смещение Y (px)", "Отступ сетки сверху в пикселях", ParamType.NUMBER),
            "spacingX"   to p("0", "Отступ X (px)", "Промежуток между кадрами по горизонтали", ParamType.NUMBER),
            "spacingY"   to p("0", "Отступ Y (px)", "Промежуток между кадрами по вертикали", ParamType.NUMBER),
            "frameW"     to p("0", "Ширина кадра (px)", "0 = авто расчет по колонкам", ParamType.NUMBER),
            "frameH"     to p("0", "Высота кадра (px)", "0 = авто расчет по строкам", ParamType.NUMBER)))

        "anim_stop" -> build("anim_stop", "Остановить анимацию", "Останавливает покадровую анимацию", BlockCategory.SPRITE, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект остановить")))

        "anim_set_frame" -> build("anim_set_frame", "Установить кадр", "Устанавливает конкретный кадр анимации", BlockCategory.SPRITE, listOf(
            "name"  to p("rect1", "Имя объекта", "Какой объект"),
            "frame" to p("0", "Номер кадра", "Индекс от 0", ParamType.NUMBER)))

        // ── Частицы (Particle System) ─────────────────────────────────────────────
        "particle_burst" -> build("particle_burst", "Взрыв частиц", "Создаёт мгновенную вспышку частиц в координатах", BlockCategory.PHYSICS, listOf(
            "x"          to p("0", "X", "Позиция X", ParamType.NUMBER),
            "y"          to p("0", "Y", "Позиция Y", ParamType.NUMBER),
            "count"      to p("20", "Количество", "Сколько частиц создать (например, 1..1000)", ParamType.NUMBER),
            "colorStart" to p("#FFAA00", "Цвет начала", "#RRGGBB"),
            "colorEnd"   to p("#FF0000", "Цвет конца", "#RRGGBB"),
            "speed"      to p("150", "Скорость разлёта", "Пикселей/сек", ParamType.NUMBER),
            "sizeStart"  to p("12", "Размер начала", "В пикселях", ParamType.NUMBER),
            "sizeEnd"    to p("2", "Размер конца", "В пикселях", ParamType.NUMBER),
            "lifetime"   to p("0.8", "Время жизни (сек)", "Длительность", ParamType.NUMBER),
            "gravity"    to p("-100", "Гравитация", "Вниз = -, вверх = +", ParamType.NUMBER)))

        "particle_emitter" -> build("particle_emitter", "Источник частиц", "Создаёт постоянный генератор частиц (огонь, дым, след)", BlockCategory.PHYSICS, listOf(
            "name"       to p("fire1", "Имя источника", "Уникальное имя эмиттера"),
            "target"     to p("rect1", "Привязать к объекту", "Имя объекта (или пусто для X/Y)"),
            "x"          to p("0", "X (если нет объекта)", "Позиция X", ParamType.NUMBER),
            "y"          to p("0", "Y (если нет объекта)", "Позиция Y", ParamType.NUMBER),
            "rate"       to p("15", "Частота (в сек)", "Сколько раз в секунду испускать", ParamType.NUMBER),
            "count"      to p("2", "Частиц за раз", "Сколько за испускание", ParamType.NUMBER),
            "speed"      to p("60", "Скорость", "Скорость разлёта", ParamType.NUMBER),
            "sizeStart"  to p("10", "Размер начала", "В пикселях", ParamType.NUMBER),
            "sizeEnd"    to p("2", "Размер конца", "В пикселях", ParamType.NUMBER),
            "colorStart" to p("#FFAA00", "Цвет начала", "#RRGGBB"),
            "colorEnd"   to p("#FF0000", "Цвет конца", "#RRGGBB"),
            "lifetime"   to p("0.6", "Время жизни (сек)", "Длительность", ParamType.NUMBER),
            "gravity"    to p("50", "Гравитация", "Вниз = -, вверх = +", ParamType.NUMBER)))

        "particle_emitter_stop" -> build("particle_emitter_stop", "Остановить частицы", "Останавливает генератор частиц", BlockCategory.PHYSICS, listOf(
            "name" to p("fire1", "Имя источника", "Имя эмиттера или all")))

        // ── Визуальные эффекты экрана ─────────────────────────────────────────────
        "screen_shake" -> build("screen_shake", "Тряска экрана", "Создаёт эффект удара или взрыва сотрясением экрана", BlockCategory.CAMERA, listOf(
            "intensity" to p("15", "Сила (px)", "Максимальное смещение в пикселях", ParamType.NUMBER),
            "duration"  to p("0.3", "Длительность (сек)", "Время затухания", ParamType.NUMBER)))

        "screen_flash" -> build("screen_flash", "Вспышка экрана", "Эффект вспышки (урон, молния, победа)", BlockCategory.CAMERA, listOf(
            "color"    to p("#FFFFFF", "Цвет", "#RRGGBB"),
            "duration" to p("0.2", "Длительность (сек)", "Время затухания", ParamType.NUMBER)))

        "camera_bounds" -> build("camera_bounds", "Границы камеры", "Ограничивает движение камеры размерами уровня", BlockCategory.CAMERA, listOf(
            "minX" to p("-1000", "Левая граница X", "Минимум по X", ParamType.NUMBER),
            "maxX" to p("1000", "Правая граница X", "Максимум по X", ParamType.NUMBER),
            "minY" to p("-1000", "Нижняя граница Y", "Минимум по Y", ParamType.NUMBER),
            "maxY" to p("1000", "Верхняя граница Y", "Максимум по Y", ParamType.NUMBER)))

        // ── Звуки и Музыка ────────────────────────────────────────────────────────
        "sound_play" -> build("sound_play", "Воспроизвести звук", "Короткий звуковой эффект через SoundPool", BlockCategory.AUDIO, listOf(
            "sound"  to p("", "Звук", "Имя звука из проекта"),
            "volume" to p("1.0", "Громкость", "0.0..1.0", ParamType.NUMBER),
            "loop"   to p("false", "Зациклить", "true = повторять, false = один раз", ParamType.SELECT),
            "rate"   to p("1.0", "Скорость", "0.5..2.0 (1.0 = норма)", ParamType.NUMBER)))

        "sound_stop" -> build("sound_stop", "Остановить звук", "Останавливает звуковой эффект", BlockCategory.AUDIO, listOf(
            "sound" to p("", "Звук", "Имя звука или all для всех")))

        "music_play" -> build("music_play", "Играть музыку", "Фоновая музыка через MediaPlayer со стримингом", BlockCategory.AUDIO, listOf(
            "sound"  to p("", "Трек", "Имя аудиофайла из проекта"),
            "volume" to p("1.0", "Громкость", "0.0..1.0", ParamType.NUMBER),
            "loop"   to p("true", "Зациклить", "true = бесконечно", ParamType.SELECT)))

        "music_pause" -> build("music_pause", "Пауза музыки", "Приостанавливает воспроизведение фоновой музыки", BlockCategory.AUDIO, emptyList())

        "music_resume" -> build("music_resume", "Возобновить музыку", "Продолжает воспроизведение фоновой музыки", BlockCategory.AUDIO, emptyList())

        "music_stop" -> build("music_stop", "Остановить музыку", "Останавливает и сбрасывает фоновую музыку", BlockCategory.AUDIO, emptyList())

        "music_volume" -> build("music_volume", "Громкость музыки", "Изменяет громкость фоновой музыки", BlockCategory.AUDIO, listOf(
            "volume" to p("1.0", "Громкость", "0.0..1.0", ParamType.NUMBER)))

        else -> null
    }
}
