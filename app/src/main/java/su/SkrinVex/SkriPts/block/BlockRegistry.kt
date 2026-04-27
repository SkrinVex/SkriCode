package su.SkrinVex.SkriPts.block

object BlockRegistry {
    data class BlockMeta(
        val type: String,
        val displayName: String,
        val description: String,
        val category: BlockCategory,
    )

    private val types = listOf(
        "set_var", "set_tag", "table_set", "table_get",
        "save_var", "load_var", "save_table", "load_table",
        "if_block", "sim_stop",
        "for_loop", "while_loop", "wait",
        "sim_create", "sim_move", "sim_resize", "sim_color", "sim_text", "sim_update_text",
        "sim_hide", "sim_show", "sim_rotate", "sim_joystick", "sim_modify", "sim_delete",
        "set_texture", "sim_sprite",
        "sim_physics", "sim_hitbox", "physics_toggle",
        "sim_camera", "camera_toggle",
        "scene_switch"
    )

    fun create(type: String) = BlockFactory.create(type)

    fun all(): List<BlockMeta> = types.mapNotNull { t ->
        BlockFactory.create(t)?.let { BlockMeta(it.type, it.displayName, it.description, it.category) }
    }

    fun byCategory(): Map<BlockCategory, List<BlockMeta>> = all().groupBy { it.category }
}
