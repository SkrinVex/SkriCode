package su.SkrinVex.SkriCode.engine.compiler

import androidx.compose.ui.graphics.Color
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.engine.ast.ExprCompiler
import su.SkrinVex.SkriCode.engine.ast.LiteralString

object BlockCompiler {

    fun compile(blocks: List<BlockDef>): List<CompiledBlock> {
        val result = mutableListOf<CompiledBlock>()
        val controlStack = java.util.ArrayDeque<ControlFrame>()
        compileInternal(blocks, result, controlStack)

        // Закрываем незакрытые кадры если были синтаксические ошибки
        while (controlStack.isNotEmpty()) {
            when (val frame = controlStack.pop()) {
                is ControlFrame.IfFrame -> (result[frame.jumpIfFalsePc] as? CompiledBlock.JumpIfFalse)?.targetPc = result.size
                is ControlFrame.ElseFrame -> (result[frame.jumpToEndPc] as? CompiledBlock.Jump)?.targetPc = result.size
                is ControlFrame.ForFrame -> (result[frame.startPc] as? CompiledBlock.ForLoopStart)?.endPc = result.size
                is ControlFrame.WhileFrame -> (result[frame.startPc] as? CompiledBlock.WhileLoopStart)?.endPc = result.size
                is ControlFrame.WaitLoopFrame -> (result[frame.startPc] as? CompiledBlock.WaitLoopStart)?.endPc = result.size
            }
        }

        return result
    }

    private fun compileInternal(
        blocks: List<BlockDef>,
        result: MutableList<CompiledBlock>,
        controlStack: java.util.ArrayDeque<ControlFrame>
    ) {
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            fun p(key: String, def: String = "", altKey: String = ""): String {
                val v1 = b.params[key]?.value
                if (!v1.isNullOrBlank()) return v1
                if (altKey.isNotBlank()) {
                    val v2 = b.params[altKey]?.value
                    if (!v2.isNullOrBlank()) return v2
                }
                return v1 ?: def
            }
            fun expr(key: String, def: String = "", altKey: String = "") = ExprCompiler.compile(p(key, def, altKey))

            when (b.type) {
                "set_var" -> result += CompiledBlock.SetVar(p("name"), expr("value", "0"))
                "set_tag" -> result += CompiledBlock.SetTag(expr("object"), expr("tag"))

                "sim_create" -> result += CompiledBlock.SimCreate(
                    nameExpr = expr("name"),
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0"),
                    widthExpr = expr("width", "100"),
                    heightExpr = expr("height", "60"),
                    radiusExpr = expr("radius", "8"),
                    colorExpr = expr("color", "#4F8EF7")
                )

                "sim_text" -> result += CompiledBlock.SimText(
                    nameExpr = expr("name"),
                    textExpr = expr("text"),
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0"),
                    widthExpr = expr("width", "200"),
                    heightExpr = expr("height", "40"),
                    sizeExpr = expr("size", "16"),
                    bold = p("bold") == "true",
                    textColorExpr = expr("textColor", "#FFFFFF")
                )

                "sim_sprite" -> result += CompiledBlock.SimSprite(
                    nameExpr = expr("name"),
                    spriteExpr = expr("sprite"),
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0"),
                    widthExpr = expr("width", "0"),
                    heightExpr = expr("height", "0"),
                    alphaExpr = expr("alpha", "1.0")
                )

                "sim_button" -> result += CompiledBlock.SimButton(
                    nameExpr = expr("name"),
                    textExpr = expr("text"),
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0"),
                    widthExpr = expr("width", "160"),
                    heightExpr = expr("height", "50"),
                    radiusExpr = expr("radius", "8"),
                    colorExpr = expr("color", "#4F8EF7"),
                    textColorExpr = expr("textColor", "#FFFFFF"),
                    sizeExpr = expr("size", "16"),
                    bold = p("bold") == "true"
                )

                "sim_text_input" -> {
                    val isMulti = p("multiline") == "true"
                    result += CompiledBlock.SimTextInput(
                        nameExpr = expr("name"),
                        placeholderExpr = expr("placeholder", "Введите текст..."),
                        initialTextExpr = expr("text", ""),
                        targetVar = p("var", "user_text").removePrefix("{").removeSuffix("}").trim(),
                        multiline = isMulti,
                        xExpr = expr("x", "0"),
                        yExpr = expr("y", "0"),
                        widthExpr = expr("width", "260"),
                        heightExpr = expr("height", if (isMulti) "90" else "52"),
                        radiusExpr = expr("radius", "8"),
                        colorExpr = expr("color", "#1E293B"),
                        textColorExpr = expr("textColor", "#FFFFFF"),
                        sizeExpr = expr("size", "15"),
                        trigger = if (isMulti) "button" else p("trigger", "keyboard"),
                        buttonTargetExpr = expr("button", "")
                    )
                }

                "sim_clear_focus" -> result += CompiledBlock.SimClearFocus

                "sim_move" -> result += CompiledBlock.SimMove(
                    targetExpr = expr("name"),
                    mode = p("mode", "instant"),
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0")
                )

                "sim_resize" -> result += CompiledBlock.SimResize(
                    targetExpr = expr("name"),
                    widthExpr = expr("width", "100"),
                    heightExpr = expr("height", "60")
                )

                "sim_color" -> result += CompiledBlock.SimColor(expr("name"), expr("color", "#EF4444"))
                "sim_update_text" -> result += CompiledBlock.SimUpdateText(expr("name"), expr("text"))
                "sim_rotate" -> result += CompiledBlock.SimRotate(expr("name"), p("mode", "instant"), expr("angle", "0"))
                "sim_hide" -> result += CompiledBlock.SimHide(expr("name", altKey = "target"))
                "sim_show" -> result += CompiledBlock.SimShow(expr("name", altKey = "target"))
                "sim_alpha" -> result += CompiledBlock.SimAlpha(expr("name", altKey = "target"), expr("alpha", "1.0"))
                "sim_touch_disable", "touch_disable", "disable_touch", "sim_disable_touch" ->
                    result += CompiledBlock.SimTouchDisable(expr("name", altKey = "target"))
                "sim_touch_enable", "touch_enable", "enable_touch", "sim_enable_touch" ->
                    result += CompiledBlock.SimTouchEnable(expr("name", altKey = "target"))
                "sim_delete" -> result += CompiledBlock.SimDelete(expr("name", altKey = "target"))

                "sim_joystick" -> {
                    fun parseColorSafe(hex: String, def: Color): Color {
                        return try {
                            val clean = hex.removePrefix("#")
                            val num = clean.toLong(16)
                            if (clean.length <= 6) Color(num or 0xFF000000) else Color(num)
                        } catch (_: Exception) { def }
                    }
                    result += CompiledBlock.SimJoystick(
                        name = p("name", "joy1"),
                        xExpr = expr("x", "-250"),
                        yExpr = expr("y", "\$screenBottom + 300"),
                        baseRadius = p("baseRadius", "100").toFloatOrNull() ?: 100f,
                        knobRadius = p("knobRadius", "40").toFloatOrNull() ?: 40f,
                        baseColor = parseColorSafe(p("baseColor"), Color(0x55000000)),
                        knobColor = parseColorSafe(p("knobColor"), Color(0xAAFFFFFF)),
                        target = p("target"),
                        speed = p("speed", "200").toFloatOrNull() ?: 200f,
                        directional = p("directional") == "true"
                    )
                }

                "sim_modify" -> {
                    val targetExpr = expr("name", altKey = "target")
                    val props = mutableListOf<Pair<String, su.SkrinVex.SkriCode.engine.ast.AstExpr>>()
                    val propList = b.children["props"].orEmpty()
                    for (pb in propList) {
                        val propName = pb.params["prop"]?.value ?: continue
                        val propExpr = ExprCompiler.compile(pb.params["value"]?.value ?: "")
                        props += Pair(propName, propExpr)
                    }
                    result += CompiledBlock.SimModify(targetExpr, props)
                }

                "sim_physics" -> result += CompiledBlock.SimPhysics(
                    targetExpr = expr("name", altKey = "target"),
                    isStatic = p("static", altKey = "isStatic") == "true",
                    gravityExpr = expr("gravity", "-9.8"),
                    bouncinessExpr = expr("bounciness", "0.0"),
                    massExpr = expr("mass", "1.0"),
                    vxExpr = expr("vx", "0.0"),
                    vyExpr = expr("vy", "0.0")
                )

                "physics_impulse" -> result += CompiledBlock.PhysicsImpulse(
                    targetExpr = expr("name", altKey = "target"),
                    vxExpr = expr("vx", "0.0"),
                    vyExpr = expr("vy", "0.0")
                )

                "physics_move" -> result += CompiledBlock.PhysicsMove(
                    targetExpr = expr("name", altKey = "target"),
                    speedExpr = expr("speed", "0.0"),
                    turnExpr = expr("turn", "0.0"),
                    frictionExpr = expr("friction", "0.9")
                )

                "sim_hitbox" -> {
                    val pointsRaw = p("points")
                    val ptsExpr = if (pointsRaw.contains(';') || pointsRaw.contains(',')) LiteralString(pointsRaw) else expr("points")
                    result += CompiledBlock.SimHitbox(
                        targetExpr = expr("name", altKey = "target"),
                        type = p("type", "auto"),
                        pointsExpr = ptsExpr
                    )
                }

                "sim_bg_color", "set_bg_color", "sim_background" -> result += CompiledBlock.SimBgColor(
                    colorExpr = expr("color", "#0F172A")
                )

                "sim_layer" -> result += CompiledBlock.SimLayer(
                    targetExpr = expr("name", altKey = "target"),
                    layerExpr = expr("layer", "0")
                )

                "sim_no_collision" -> result += CompiledBlock.SimNoCollision(
                    targetExpr = expr("name", altKey = "target"),
                    ignoreExpr = expr("ignore")
                )

                "sim_restore_collision" -> result += CompiledBlock.SimRestoreCollision(
                    targetExpr = expr("name", altKey = "target"),
                    targetUnignoreExpr = expr("target", "all", altKey = "ignore")
                )

                "physics_toggle" -> result += CompiledBlock.PhysicsToggle(expr("enabled", "true"))

                "set_texture" -> result += CompiledBlock.SetTexture(
                    targetExpr = expr("name", altKey = "target"),
                    spriteExpr = expr("sprite"),
                    alphaExpr = expr("alpha", "1.0"),
                    scaleXExpr = expr("scaleX", "1.0"),
                    scaleYExpr = expr("scaleY", "1.0"),
                    cropXExpr = expr("cropX", "0"),
                    cropYExpr = expr("cropY", "0"),
                    cropWExpr = expr("cropW", "0"),
                    cropHExpr = expr("cropH", "0")
                )

                "anim_play" -> result += CompiledBlock.AnimPlay(
                    targetExpr = expr("name", altKey = "target"),
                    spriteExpr = expr("sprite"),
                    colsExpr = expr("cols", "4"),
                    rowsExpr = expr("rows", "1"),
                    startFrameExpr = expr("startFrame", "0"),
                    endFrameExpr = expr("endFrame", "0"),
                    fpsExpr = expr("fps", "12"),
                    loopExpr = expr("loop", "true"),
                    offsetXExpr = expr("offsetX", "0"),
                    offsetYExpr = expr("offsetY", "0"),
                    spacingXExpr = expr("spacingX", "0"),
                    spacingYExpr = expr("spacingY", "0"),
                    frameWExpr = expr("frameW", "0"),
                    frameHExpr = expr("frameH", "0")
                )

                "anim_stop" -> result += CompiledBlock.AnimStop(expr("name", altKey = "target"))
                "anim_set_frame" -> result += CompiledBlock.AnimSetFrame(expr("name", altKey = "target"), expr("frame", "0"))

                "particle_burst" -> result += CompiledBlock.ParticleBurst(
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0"),
                    countExpr = expr("count", "20"),
                    colorStartExpr = expr("colorStart", "#FFAA00"),
                    colorEndExpr = expr("colorEnd", "#FF0000"),
                    speedExpr = expr("speed", "150"),
                    sizeStartExpr = expr("sizeStart", "12"),
                    sizeEndExpr = expr("sizeEnd", "2"),
                    lifetimeExpr = expr("lifetime", "0.8"),
                    gravityExpr = expr("gravity", "-100")
                )

                "particle_emitter" -> result += CompiledBlock.ParticleEmitterCreate(
                    name = p("name", "emitter1"),
                    targetExpr = expr("target"),
                    xExpr = expr("x", "0"),
                    yExpr = expr("y", "0"),
                    rateExpr = expr("rate", "15"),
                    countExpr = expr("count", "2"),
                    speedExpr = expr("speed", "60"),
                    sizeStartExpr = expr("sizeStart", "10"),
                    sizeEndExpr = expr("sizeEnd", "2"),
                    colorStartExpr = expr("colorStart", "#FFAA00"),
                    colorEndExpr = expr("colorEnd", "#FF0000"),
                    lifetimeExpr = expr("lifetime", "0.6"),
                    gravityExpr = expr("gravity", "50")
                )

                "particle_emitter_stop" -> result += CompiledBlock.ParticleEmitterStop(p("name", "emitter1"))

                "screen_shake" -> result += CompiledBlock.ScreenShake(
                    intensityExpr = expr("intensity", "10"),
                    durationExpr = expr("duration", "0.3")
                )
                "screen_flash" -> result += CompiledBlock.ScreenFlash(
                    colorExpr = expr("color", "#FFFFFF"),
                    durationExpr = expr("duration", "0.2")
                )
                "camera_bounds" -> result += CompiledBlock.CameraBounds(expr("minX", "-1000"), expr("maxX", "1000"), expr("minY", "-1000"), expr("maxY", "1000"))
                "camera_zoom" -> result += CompiledBlock.CameraZoom(expr("name", "cam1"), expr("zoom", "1.0"), expr("smoothing", "1.0"))

                "sim_camera" -> result += CompiledBlock.SimCameraBlock(
                    name = p("name", "cam1"),
                    targetExpr = expr("target"),
                    smoothingExpr = expr("smoothing", "0.1"),
                    uiTagsExpr = expr("ui_tags", ""),
                    enabled = p("enabled", "true") == "true"
                )

                "camera_toggle" -> result += CompiledBlock.CameraToggle(p("name", "cam1"), expr("enabled", "true"))

                "table_set" -> result += CompiledBlock.TableSet(expr("table"), expr("key"), expr("value"))
                "table_get" -> result += CompiledBlock.TableGet(expr("table"), expr("key"), p("var"))
                "save_var" -> result += CompiledBlock.SaveVar(expr("key"), expr("value"), p("encrypt") == "true")
                "load_var" -> result += CompiledBlock.LoadVar(expr("key"), p("var"), expr("default", "0"), p("encrypt") == "true")
                "save_table" -> result += CompiledBlock.SaveTable(expr("key"), expr("table"), p("encrypt") == "true")
                "load_table" -> result += CompiledBlock.LoadTable(expr("key"), expr("table"), p("encrypt") == "true")

                "sound_play" -> result += CompiledBlock.SoundPlay(expr("sound"), expr("volume", "1.0"), expr("loop", "false"), expr("rate", "1.0"))
                "sound_stop" -> result += CompiledBlock.SoundStop(expr("sound"))
                "music_play" -> result += CompiledBlock.MusicPlay(expr("music", altKey = "sound"), expr("volume", "1.0"), expr("loop", "true"))
                "music_pause" -> result += CompiledBlock.MusicPause
                "music_resume" -> result += CompiledBlock.MusicResume
                "music_stop" -> result += CompiledBlock.MusicStop
                "music_volume" -> result += CompiledBlock.MusicVolume(expr("volume", "1.0"))

                "scene_switch" -> result += CompiledBlock.SceneSwitch(expr("scene"))
                "sim_stop" -> result += CompiledBlock.SimStop

                "call_func" -> {
                    val rawArgs = p("args").trim()
                    val argsList = if (rawArgs.isBlank()) emptyList() else {
                        rawArgs.split(",").map { ExprCompiler.compile(it.trim()) }
                    }
                    val retVar = p("return_var").removePrefix("{").removeSuffix("}").trim()
                    result += CompiledBlock.CallFunc(
                        funcNameExpr = expr("name", "myFunc"),
                        argsExpr = argsList,
                        returnVar = retVar
                    )
                }

                "return_val" -> result += CompiledBlock.ReturnVal(expr("value", "0"))

                // ── Управляющие конструкции (Open / Close) ────────────────────
                "if_open" -> {
                    val cond = ExprCompiler.compileCondition(p("left"), p("op", "=="), p("right", "0"))
                    val jumpIfFalse = CompiledBlock.JumpIfFalse(cond, targetPc = -1)
                    val pc = result.size
                    result += jumpIfFalse
                    controlStack.push(ControlFrame.IfFrame(jumpIfFalsePc = pc))
                }

                "else_block" -> {
                    if (controlStack.isNotEmpty() && controlStack.peek() is ControlFrame.IfFrame) {
                        val ifFrame = controlStack.pop() as ControlFrame.IfFrame
                        val jumpToEnd = CompiledBlock.Jump(targetPc = -1)
                        val jumpToEndPc = result.size
                        result += jumpToEnd

                        // Перенаправляем JumpIfFalse на блок после else_block
                        (result[ifFrame.jumpIfFalsePc] as? CompiledBlock.JumpIfFalse)?.targetPc = result.size
                        controlStack.push(ControlFrame.ElseFrame(jumpToEndPc = jumpToEndPc))
                    }
                }

                "if_close" -> {
                    if (controlStack.isNotEmpty()) {
                        when (val frame = controlStack.pop()) {
                            is ControlFrame.IfFrame -> {
                                (result[frame.jumpIfFalsePc] as? CompiledBlock.JumpIfFalse)?.targetPc = result.size
                            }
                            is ControlFrame.ElseFrame -> {
                                (result[frame.jumpToEndPc] as? CompiledBlock.Jump)?.targetPc = result.size
                            }
                            else -> controlStack.push(frame)
                        }
                    }
                }

                "for_loop_open" -> {
                    val loopId = "for_${result.size}"
                    val start = CompiledBlock.ForLoopStart(expr("count", "1"), loopId, endPc = -1)
                    val pc = result.size
                    result += start
                    controlStack.push(ControlFrame.ForFrame(startPc = pc, loopId = loopId))
                }

                "for_loop_close" -> {
                    if (controlStack.isNotEmpty() && controlStack.peek() is ControlFrame.ForFrame) {
                        val frame = controlStack.pop() as ControlFrame.ForFrame
                        val end = CompiledBlock.ForLoopEnd(frame.loopId, startPc = frame.startPc)
                        result += end
                        (result[frame.startPc] as? CompiledBlock.ForLoopStart)?.endPc = result.size
                    }
                }

                "while_loop_open" -> {
                    val cond = ExprCompiler.compileCondition(p("left"), p("op", "<="), p("right", "10"))
                    val start = CompiledBlock.WhileLoopStart(cond, endPc = -1)
                    val pc = result.size
                    result += start
                    controlStack.push(ControlFrame.WhileFrame(startPc = pc))
                }

                "while_loop_close" -> {
                    if (controlStack.isNotEmpty() && controlStack.peek() is ControlFrame.WhileFrame) {
                        val frame = controlStack.pop() as ControlFrame.WhileFrame
                        val end = CompiledBlock.WhileLoopEnd(startPc = frame.startPc)
                        result += end
                        (result[frame.startPc] as? CompiledBlock.WhileLoopStart)?.endPc = result.size
                    }
                }

                "wait_open" -> {
                    val countStr = p("count", "1").trim()
                    val countNum = countStr.toDoubleOrNull()
                    val isSingleDelay = countNum == 1.0 || (countStr.isEmpty() && b.params["count"] == null)
                    val secExpr = expr("seconds", "1")
                    val countExpr = expr("count", "1")

                    if (isSingleDelay) {
                        result += CompiledBlock.WaitDelay(secExpr)
                    } else {
                        val loopId = "wait_${result.size}"
                        val start = CompiledBlock.WaitLoopStart(secExpr, countExpr, loopId, endPc = -1)
                        val pc = result.size
                        result += start
                        controlStack.push(ControlFrame.WaitLoopFrame(startPc = pc, loopId = loopId))
                    }
                }

                "wait_close" -> {
                    if (controlStack.isNotEmpty() && controlStack.peek() is ControlFrame.WaitLoopFrame) {
                        val frame = controlStack.pop() as ControlFrame.WaitLoopFrame
                        val end = CompiledBlock.WaitLoopEnd(frame.loopId, startPc = frame.startPc)
                        result += end
                        (result[frame.startPc] as? CompiledBlock.WaitLoopStart)?.endPc = result.size
                    }
                }

                // Legacy-блоки с вложенными children
                "if_block" -> {
                    val cond = ExprCompiler.compileCondition(p("left"), p("op", "=="), p("right", "0"))
                    val thenBlocks = b.children["then"].orEmpty()
                    val elseBlocks = b.children["else"].orEmpty()

                    val jumpIfFalse = CompiledBlock.JumpIfFalse(cond, targetPc = -1)
                    result += jumpIfFalse

                    compileInternal(thenBlocks, result, controlStack)

                    if (elseBlocks.isNotEmpty()) {
                        val jumpToEnd = CompiledBlock.Jump(targetPc = -1)
                        result += jumpToEnd
                        jumpIfFalse.targetPc = result.size
                        compileInternal(elseBlocks, result, controlStack)
                        jumpToEnd.targetPc = result.size
                    } else {
                        jumpIfFalse.targetPc = result.size
                    }
                }

                "for_loop" -> {
                    val loopId = "for_${result.size}"
                    val startPc = result.size
                    val start = CompiledBlock.ForLoopStart(expr("count", "1"), loopId, endPc = -1)
                    result += start
                    compileInternal(b.children["body"].orEmpty(), result, controlStack)
                    result += CompiledBlock.ForLoopEnd(loopId, startPc = startPc)
                    start.endPc = result.size
                }

                "while_loop" -> {
                    val cond = ExprCompiler.compileCondition(p("left"), p("op", "<="), p("right", "10"))
                    val startPc = result.size
                    val start = CompiledBlock.WhileLoopStart(cond, endPc = -1)
                    result += start
                    compileInternal(b.children["body"].orEmpty(), result, controlStack)
                    result += CompiledBlock.WhileLoopEnd(startPc = startPc)
                    start.endPc = result.size
                }

                "wait" -> {
                    val sub = mutableListOf<CompiledBlock>()
                    val subStack = java.util.ArrayDeque<ControlFrame>()
                    compileInternal(b.children["body"].orEmpty(), sub, subStack)
                    result += CompiledBlock.WaitTimer(expr("seconds", "1"), expr("count", "1"), sub)
                }
            }
            i++
        }
    }

    private sealed interface ControlFrame {
        data class IfFrame(val jumpIfFalsePc: Int) : ControlFrame
        data class ElseFrame(val jumpToEndPc: Int) : ControlFrame
        data class ForFrame(val startPc: Int, val loopId: String) : ControlFrame
        data class WhileFrame(val startPc: Int) : ControlFrame
        data class WaitLoopFrame(val startPc: Int, val loopId: String) : ControlFrame
    }
}
