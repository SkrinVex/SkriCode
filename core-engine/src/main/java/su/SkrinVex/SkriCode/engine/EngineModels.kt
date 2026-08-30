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
    val alpha: Float = 1f,
    val touchEnabled: Boolean = true,
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
    val spriteCropH: Int = 0,
    // Покадровая анимация спрайт-листа
    val animCols: Int = 1,
    val animRows: Int = 1,
    val animFps: Float = 0f,
    val animStartFrame: Int = 0,
    val animEndFrame: Int = 0,
    val animLoop: Boolean = true,
    val animPlaying: Boolean = false,
    val animCurrentFrame: Int = 0,
    val animElapsed: Float = 0f,
    val animOffsetX: Int = 0,
    val animOffsetY: Int = 0,
    val animSpacingX: Int = 0,
    val animSpacingY: Int = 0,
    val animFrameWidth: Int = 0,
    val animFrameHeight: Int = 0,
    // Текстовое поле ввода (виджет)
    val isTextInput: Boolean = false,
    val multiline: Boolean = false,
    val placeholder: String = "",
    val targetVar: String = "",
    val inputTrigger: String = "keyboard", // "keyboard" | "button"
    val inputButton: String = ""           // имя или #тег объекта для подтверждения
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
    val zoom: Float = 1f,          // текущий масштаб (1 = 100%)
    val targetZoom: Float = 1f,    // целевой масштаб
    val zoomSmoothing: Float = 1f, // плавность изменения зума (1 = мгновенно, 0.05 = плавно)
    // runtime — текущее смещение камеры
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    // Ограничение границами мира
    val boundMinX: Float? = null,
    val boundMaxX: Float? = null,
    val boundMinY: Float? = null,
    val boundMaxY: Float? = null
)

/** Отдельная частица */
data class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val life: Float,
    val maxLife: Float,
    val sizeStart: Float,
    val sizeEnd: Float,
    val colorStart: Color,
    val colorEnd: Color,
    val gravity: Float = 0f,
    val rotation: Float = 0f,
    val vRot: Float = 0f
)

/** Эмиттер (постоянный источник) частиц */
data class ParticleEmitterState(
    val name: String,
    val targetName: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val rate: Float = 10f,             // испусканий в секунду
    val countPerEmission: Int = 2,
    val speed: Float = 60f,
    val sizeStart: Float = 12f,
    val sizeEnd: Float = 2f,
    val colorStart: Color = Color(0xFFFFAA00),
    val colorEnd: Color = Color(0xFFFF0000),
    val lifetime: Float = 0.8f,
    val gravity: Float = 0f,
    val enabled: Boolean = true,
    val timer: Float = 0f
)

/** Тряска экрана */
data class ScreenShakeState(
    val intensity: Float = 0f,
    val duration: Float = 0f,
    val elapsed: Float = 0f,
    val currentOffsetX: Float = 0f,
    val currentOffsetY: Float = 0f
)

/** Вспышка экрана */
data class ScreenFlashState(
    val color: Color = Color.White,
    val duration: Float = 0f,
    val elapsed: Float = 0f,
    val active: Boolean = false
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
    val projectId: String = "",
    val backgroundColor: Color = Color(0xFF0F172A),
    // Новые системы
    val particles: List<Particle> = emptyList(),
    val particleEmitters: Map<String, ParticleEmitterState> = emptyMap(),
    val screenShake: ScreenShakeState = ScreenShakeState(),
    val screenFlash: ScreenFlashState = ScreenFlashState(),
    val clearFocusTrigger: Long = 0L
)

/**
 * Область видимости данных для вычисления выражений.
 */
data class ExprScope(
    var objects: Map<String, SimObject> = emptyMap(),
    var joysticks: Map<String, JoystickState> = emptyMap(),
    var tables: Map<String, Map<String, String>> = emptyMap(),
    var functions: Map<String, su.SkrinVex.SkriCode.data.Script> = emptyMap(),
    var customFuncEvaluator: ((name: String, args: List<String>, scope: ExprScope) -> String)? = null
)

data class EvalResult(val value: String, val error: String? = null)
