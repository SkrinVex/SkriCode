package su.SkrinVex.SkriCode.engine

import androidx.compose.ui.graphics.Color
import su.SkrinVex.SkriCode.data.SpriteAsset

/** Тип хитбокса */
enum class HitboxType { AUTO, MANUAL }

/** Хитбокс объекта */
data class Hitbox(
    val type: HitboxType = HitboxType.AUTO,
    /** Для MANUAL — список точек в локальных координатах объекта (относительно центра) */
    val points: List<Pair<Float, Float>> = emptyList()
)

/** Физическое тело объекта */
data class PhysicsBody(
    val enabled: Boolean = true,
    val gravity: Float = -9.8f,   // px/tick² (отрицательное = вниз)
    val isStatic: Boolean = false, // статик нельзя двигать физикой/джойстиком
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val mass: Float = 1f,
    val bounciness: Float = 0f    // 0..1
)

data class SimObject(
    val name: String,
    val x: Float, val y: Float,
    val width: Float, val height: Float,
    val radius: Float,
    val color: Color,
    val label: String = "",
    val fontSize: Float = 14f,
    val bold: Boolean = false,
    val textColor: Color? = null,
    val tapScriptId: String? = null,
    val holdScriptId: String? = null,
    val collisionScriptId: String? = null,
    val collisionEndScriptId: String? = null,
    val visible: Boolean = true,
    val rotation: Float = 0f,
    val tags: Set<String> = emptySet(),
    val physicsBody: PhysicsBody? = null,
    val hitbox: Hitbox = Hitbox(),
    val zOrder: Int = 0,
    /** Имена или теги (#tag) объектов с которыми не считать коллизии */
    val collisionIgnore: Set<String> = emptySet(),
    // Текстура
    val spriteName: String? = null,
    val spriteAlpha: Float = 1f,
    val spriteScaleX: Float = 1f,
    val spriteScaleY: Float = 1f,
    val spriteCropX: Int = 0,
    val spriteCropY: Int = 0,
    val spriteCropW: Int = 0,
    val spriteCropH: Int = 0
)

data class JoystickState(
    val name: String,
    val x: Float, val y: Float,          // позиция центра джойстика
    val baseRadius: Float,               // радиус базы
    val knobRadius: Float,               // радиус ручки
    val baseColor: Color,
    val knobColor: Color,
    val targetObject: String,            // имя объекта которым управляет
    val speed: Float,                    // скорость движения px/tick
    val directional: Boolean,            // вращать объект по направлению
    val visible: Boolean = true,
    // runtime
    val knobDx: Float = 0f,             // смещение ручки от центра (-1..1)
    val knobDy: Float = 0f,
    val pointerId: Long? = null          // какой палец держит
)

/** Камера слежения */
data class SimCamera(
    val name: String,
    val enabled: Boolean = true,
    val targetName: String = "",   // имя объекта за которым следим
    val uiTags: Set<String> = emptySet(), // теги объектов-интерфейса (не двигаются с камерой)
    val smoothing: Float = 1f,     // 0..1: 1 = мгновенно, 0.05 = очень плавно
    // runtime — текущее смещение камеры
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

data class SimState(
    val objects: Map<String, SimObject> = emptyMap(),
    val joysticks: Map<String, JoystickState> = emptyMap(),
    val globalVars: Map<String, String> = emptyMap(),
    val tables: Map<String, Map<String, String>> = emptyMap(),
    val log: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val isStopped: Boolean = false,
    val physicsEnabled: Boolean = true,
    val activeCollisions: Set<Pair<String, String>> = emptySet(),
    val camera: SimCamera? = null,
    val pendingSceneSwitch: String? = null,  // имя сцены для перехода
    val sprites: List<SpriteAsset> = emptyList(),
    val projectId: String = ""
)

/**
 * Область видимости данных для вычисления выражений.
 */
data class ExprScope(
    var objects: Map<String, SimObject> = emptyMap(),
    var joysticks: Map<String, JoystickState> = emptyMap(),
    var tables: Map<String, Map<String, String>> = emptyMap(),
)

data class EvalResult(val value: String, val error: String? = null)
