package su.SkrinVex.SkriPts.block

object BlockRegistry {
    data class BlockMeta(
        val type: String,
        val displayName: String,
        val description: String,
        val category: BlockCategory,
    )

    private val types = listOf(
        "set_var",
        "sim_create", "sim_move", "sim_resize", "sim_color", "sim_label", "sim_text", "sim_update_text"
    )

    fun create(type: String) = BlockFactory.create(type)

    fun all(): List<BlockMeta> = types.mapNotNull { t ->
        BlockFactory.create(t)?.let { BlockMeta(it.type, it.displayName, it.description, it.category) }
    }

    fun byCategory(): Map<BlockCategory, List<BlockMeta>> = all().groupBy { it.category }
}
