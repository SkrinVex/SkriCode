package su.SkrinVex.SkriPts.block

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

        "sim_label" -> build("sim_label", "Текст на объекте", "Устанавливает текст внутри прямоугольника", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект"),
            "text" to p("", "Текст", "Что написать")))

        "sim_update_text" -> build("sim_update_text", "Обновить текст", "Меняет текст текстового объекта", BlockCategory.SIMULATION, listOf(
            "name" to p("text1", "Имя объекта", "Имя sim_text объекта"),
            "text" to p("", "Новый текст", "Поддерживает {переменные}")))

        "sim_text" -> build("sim_text", "Текстовый объект", "Создаёт текст на сцене", BlockCategory.SIMULATION, listOf(
            "name"   to p("text1", "Имя объекта", "Уникальное имя"),
            "text"   to p("Привет!", "Текст", "Поддерживает {переменные}"),
            "x"      to p("0", "X", "Позиция X", ParamType.NUMBER),
            "y"      to p("0", "Y", "Позиция Y", ParamType.NUMBER),
            "width"  to p("200", "Ширина", "Ширина области", ParamType.NUMBER),
            "height" to p("40", "Высота", "Высота области", ParamType.NUMBER),
            "size"   to p("16", "Размер шрифта", "В пикселях", ParamType.NUMBER),
            "bold"   to p("false", "Жирный", "true или false")))

        "if_block" -> build("if_block", "Условие (если)", "Выполняет блоки если условие истинно", BlockCategory.CONTROL, listOf(
            "left"  to p("", "Левое значение", "Переменная или число, напр. {score}"),
            "op"    to p("==", "Оператор", "== != > < >= <=", ParamType.SELECT),
            "right" to p("0", "Правое значение", "С чем сравниваем")))

        "sim_stop" -> build("sim_stop", "Завершить симуляцию", "Останавливает выполнение скрипта", BlockCategory.CONTROL, emptyList())

        // Циклы
        "for_loop" -> build("for_loop", "Цикл (повторить)", "Повторяет блоки указанное количество раз", BlockCategory.CONTROL, listOf(
            "count" to p("5", "Количество", "Сколько раз повторить", ParamType.NUMBER))).copy(
            children = mapOf("body" to emptyList()))

        "while_loop" -> build("while_loop", "Цикл (пока)", "Повторяет блоки пока условие истинно", BlockCategory.CONTROL, listOf(
            "left"  to p("", "Левое значение", "Переменная или число"),
            "op"    to p("<=", "Оператор", "== != > < >= <=", ParamType.SELECT),
            "right" to p("10", "Правое значение", "С чем сравниваем"))).copy(
            children = mapOf("body" to emptyList()))

        // Таймер
        "wait" -> build("wait", "Ждать", "Приостанавливает выполнение на время", BlockCategory.CONTROL, listOf(
            "seconds" to p("1", "Секунды", "Время ожидания", ParamType.NUMBER)))

        // Видимость объектов
        "sim_hide" -> build("sim_hide", "Скрыть объект", "Делает объект невидимым и неклкабельным", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект скрыть")))

        "sim_show" -> build("sim_show", "Показать объект", "Делает объект видимым и кликабельным", BlockCategory.SIMULATION, listOf(
            "name" to p("rect1", "Имя объекта", "Какой объект показать")))

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

        else -> null
    }
}
