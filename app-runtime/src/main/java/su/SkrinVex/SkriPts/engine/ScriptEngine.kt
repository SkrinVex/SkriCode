package su.SkrinVex.SkriPts.engine

import su.SkrinVex.SkriPts.block.BlockDef
import com.google.gson.JsonArray

object ScriptEngine {
    init { System.loadLibrary("SkriPts") }

    fun execute(blocks: List<BlockDef>): List<String> {
        val arr = JsonArray()
        blocks.forEach { arr.add(it.toJson()) }
        return nativeExecute(arr.toString()).split("\n").filter { it.isNotBlank() }
    }

    private external fun nativeExecute(blocksJson: String): String
}
