package su.SkrinVex.SkriCode.block

object BlockRegistry {
    data class BlockMeta(
        val type: String,
        val displayName: String,
        val description: String,
        val category: BlockCategory,
    )

    // Типы показываемые в пикере блоков (закрывающие и else не показываем — добавляются автоматически)
    private val types = listOf(
        "set_var", "set_tag", "table_set", "table_get",
        "save_var", "load_var", "save_table", "load_table",
        "if_open", "sim_stop",
        "for_loop_open", "while_loop_open", "wait_open",
        "sim_create", "sim_move", "sim_resize", "sim_color", "sim_text", "sim_update_text",
        "sim_hide", "sim_show", "sim_touch_disable", "sim_touch_enable", "sim_rotate", "sim_joystick", "sim_modify", "sim_delete", "sim_layer",
        "set_texture", "sim_sprite", "anim_play", "anim_stop", "anim_set_frame",
        "sim_physics", "sim_hitbox", "physics_toggle",
        "physics_impulse", "physics_move", "sim_no_collision",
        "particle_burst", "particle_emitter", "particle_emitter_stop",
        "sim_camera", "camera_toggle", "camera_bounds", "screen_shake", "screen_flash",
        "sound_play", "sound_stop", "music_play", "music_pause", "music_resume", "music_stop", "music_volume",
        "scene_switch"
    )

    fun create(type: String) = BlockFactory.create(type)

    fun all(): List<BlockMeta> = types.mapNotNull { t ->
        BlockFactory.create(t)?.let { BlockMeta(it.type, it.displayName, it.description, it.category) }
    }

    fun byCategory(): Map<BlockCategory, List<BlockMeta>> = all().groupBy { it.category }
}
