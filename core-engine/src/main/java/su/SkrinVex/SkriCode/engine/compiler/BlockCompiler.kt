package su.SkrinVex.SkriCode.engine.compiler

import androidx.compose.ui.graphics.Color
import su.SkrinVex.SkriCode.block.BlockDef
import su.SkrinVex.SkriCode.engine.ast.ExprCompiler
import su.SkrinVex.SkriCode.engine.ast.LiteralString

object BlockCompiler {

    fun compile(blocks: List<BlockDef>): List<CompiledBlock> {
        val result = mutableListOf<CompiledBlock>()
        val controlStack = java.util.ArrayDeque<ControlFrame>()

        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            fun p(key: String, def: String = ""): String = b.params[key]?.value ?: def
            fun expr(key: String, def: String = "") = ExprCompiler.compile(p(key, def))

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
                "sim_hide" -> result += CompiledBlock.SimHide(expr("name"))
                "sim_show" -> result += CompiledBlock.SimShow(expr("name"))
                "sim_delete" -> result += CompiledBlock.SimDelete(expr("name"))

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
                        baseColor = parseColorSafe(p("baseColor"), Color(0xFF334466)),
                        knobColor = parseColorSafe(p("knobColor"), Color(0xFF4F8EF7)),
                        target = p("target"),
                        speed = p("speed", "8").toFloatOrNull() ?: 8f,
                        directional = p("directional") == "true"
                    )
                }

                "sim_modify" -> {
                    val childProps = b.children["props"].orEmpty().mapNotNull { propBlock ->
                        val propKey = propBlock.params["prop"]?.value?.trim() ?: return@mapNotNull null
                        val propVal = propBlock.params["value"]?.value ?: ""
                        if (propKey.isNotBlank()) propKey to ExprCompiler.compile(propVal) else null
                    }
                    result += CompiledBlock.SimModify(expr("name"), childProps)
                }

                "sim_layer" -> result += CompiledBlock.SimLayer(expr("name"), expr("layer", "0"))

                "set_texture" -> result += CompiledBlock.SetTexture(
                    targetExpr = expr("name"),
                    spriteExpr = expr("sprite"),
                    scaleXExpr = expr("scaleX", "1.0"),
                    scaleYExpr = expr("scaleY", "1.0"),
                    alphaExpr = expr("alpha", "1.0"),
                    cropXExpr = expr("cropX", "0"),
                    cropYExpr = expr("cropY", "0"),
                    cropWExpr = expr("cropW", "0"),
                    cropHExpr = expr("cropH", "0")
                )

                "anim_play" -> result += CompiledBlock.AnimPlay(
                    targetExpr = expr("name"),
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

                "anim_stop" -> result += CompiledBlock.AnimStop(expr("name"))
                "anim_set_frame" -> result += CompiledBlock.AnimSetFrame(expr("name"), expr("frame", "0"))

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
                    name = p("name", "fire1"),
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

                "particle_emitter_stop" -> result += CompiledBlock.ParticleEmitterStop(p("name", "fire1"))

                "screen_shake" -> result += CompiledBlock.ScreenShake(expr("intensity", "15"), expr("duration", "0.3"))
                "screen_flash" -> result += CompiledBlock.ScreenFlash(expr("color", "#FFFFFF"), expr("duration", "0.2"))
                "camera_bounds" -> result += CompiledBlock.CameraBounds(expr("minX", "-1000"), expr("maxX", "1000"), expr("minY", "-1000"), expr("maxY", "1000"))

                "sim_physics" -> result += CompiledBlock.SimPhysics(
                    targetExpr = expr("name"),
                    gravityExpr = expr("gravity", "-9.8"),
                    isStatic = p("static") == "true",
                    bouncinessExpr = expr("bounciness", "0"),
                    massExpr = expr("mass", "1"),
                    vxExpr = expr("vx", "0"),
                    vyExpr = expr("vy", "0")
                )

                "physics_impulse" -> result += CompiledBlock.PhysicsImpulse(
                    targetExpr = expr("name"),
                    vxExpr = expr("vx", "0"),
                    vyExpr = expr("vy", "500")
                )

                "physics_move" -> result += CompiledBlock.PhysicsMove(
                    targetExpr = expr("name"),
                    speedExpr = expr("speed", "0"),
                    turnExpr = expr("turn", "0"),
                    frictionExpr = expr("friction", "0.9")
                )

                "sim_hitbox" -> result += CompiledBlock.SimHitbox(expr("name"), p("type", "auto"), expr("points"))
                "sim_no_collision" -> result += CompiledBlock.SimNoCollision(expr("name"), expr("ignore"))
                "physics_toggle" -> result += CompiledBlock.PhysicsToggle(expr("enabled", "true"))

                "sim_camera" -> result += CompiledBlock.SimCameraBlock(
                    name = p("name", "cam1"),
                    targetExpr = expr("target"),
                    smoothingExpr = expr("smoothing", "0.1"),
                    uiTags = p("ui_tags").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
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
                "music_play" -> result += CompiledBlock.MusicPlay(expr("sound"), expr("volume", "1.0"), expr("loop", "true"))
                "music_pause" -> result += CompiledBlock.MusicPause
                "music_resume" -> result += CompiledBlock.MusicResume
                "music_stop" -> result += CompiledBlock.MusicStop
                "music_volume" -> result += CompiledBlock.MusicVolume(expr("volume", "1.0"))

                "scene_switch" -> result += CompiledBlock.SceneSwitch(expr("scene"))
                "sim_stop" -> result += CompiledBlock.SimStop

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
                    // Ищем парный wait_close и рекурсивно компилируем тело
                    val subList = mutableListOf<BlockDef>()
                    var depth = 1
                    var k = i + 1
                    while (k < blocks.size && depth > 0) {
                        val cb = blocks[k]
                        if (cb.type == "wait_open") depth++
                        else if (cb.type == "wait_close") {
                            depth--
                            if (depth == 0) break
                        }
                        subList.add(cb)
                        k++
                    }
                    val compiledSub = compile(subList)
                    result += CompiledBlock.WaitTimer(expr("seconds", "1"), expr("count", "1"), compiledSub)
                    i = k // пропускаем обработанные блоки
                }

                // Legacy-блоки с вложенными children
                "if_block" -> {
                    val cond = ExprCompiler.compileCondition(p("left"), p("op", "=="), p("right", "0"))
                    val thenBlocks = b.children["then"].orEmpty()
                    val elseBlocks = b.children["else"].orEmpty()

                    val compiledThen = compile(thenBlocks)
                    val compiledElse = compile(elseBlocks)

                    val jumpIfFalse = CompiledBlock.JumpIfFalse(cond, targetPc = -1)
                    result += jumpIfFalse

                    result.addAll(compiledThen)
                    if (compiledElse.isNotEmpty()) {
                        val jumpToEnd = CompiledBlock.Jump(targetPc = -1)
                        result += jumpToEnd
                        jumpIfFalse.targetPc = result.size
                        result.addAll(compiledElse)
                        jumpToEnd.targetPc = result.size
                    } else {
                        jumpIfFalse.targetPc = result.size
                    }
                }

                "for_loop" -> {
                    val sub = compile(b.children["body"].orEmpty())
                    val loopId = "for_leg_${result.size}"
                    val start = CompiledBlock.ForLoopStart(expr("count", "1"), loopId, endPc = -1)
                    val pc = result.size
                    result += start
                    result.addAll(sub)
                    val end = CompiledBlock.ForLoopEnd(loopId, startPc = pc)
                    result += end
                    start.endPc = result.size
                }

                "while_loop" -> {
                    val sub = compile(b.children["body"].orEmpty())
                    val cond = ExprCompiler.compileCondition(p("left"), p("op", "<="), p("right", "10"))
                    val start = CompiledBlock.WhileLoopStart(cond, endPc = -1)
                    val pc = result.size
                    result += start
                    result.addAll(sub)
                    val end = CompiledBlock.WhileLoopEnd(startPc = pc)
                    result += end
                    start.endPc = result.size
                }

                "wait" -> {
                    val sub = compile(b.children["body"].orEmpty())
                    result += CompiledBlock.WaitTimer(expr("seconds", "1"), expr("count", "1"), sub)
                }
            }
            i++
        }

        // Закрываем незакрытые кадры если были синтаксические ошибки
        while (controlStack.isNotEmpty()) {
            when (val frame = controlStack.pop()) {
                is ControlFrame.IfFrame -> (result[frame.jumpIfFalsePc] as? CompiledBlock.JumpIfFalse)?.targetPc = result.size
                is ControlFrame.ElseFrame -> (result[frame.jumpToEndPc] as? CompiledBlock.Jump)?.targetPc = result.size
                is ControlFrame.ForFrame -> (result[frame.startPc] as? CompiledBlock.ForLoopStart)?.endPc = result.size
                is ControlFrame.WhileFrame -> (result[frame.startPc] as? CompiledBlock.WhileLoopStart)?.endPc = result.size
            }
        }

        return result
    }

    private sealed interface ControlFrame {
        data class IfFrame(val jumpIfFalsePc: Int) : ControlFrame
        data class ElseFrame(val jumpToEndPc: Int) : ControlFrame
        data class ForFrame(val startPc: Int, val loopId: String) : ControlFrame
        data class WhileFrame(val startPc: Int) : ControlFrame
    }
}
