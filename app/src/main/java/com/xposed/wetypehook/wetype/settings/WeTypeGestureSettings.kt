package com.xposed.wetypehook.wetype.settings

import com.xposed.wetypehook.wetype.gesture.GestureAction
import org.json.JSONObject

/**
 * 手势按键绑定配置序列化与反序列化
 */
object WeTypeGestureSettings {

    private data class BindingsCache(val json: String, val bindings: Map<Char, GestureAction>)

    @Volatile
    private var bindingsCache: BindingsCache? = null

    /** Shared by touch handling and label drawing; publish the source and result together. */
    fun cachedBindings(json: String): Map<Char, GestureAction> {
        bindingsCache?.takeIf { it.json == json }?.let { return it.bindings }
        return parseBindings(json).also { bindingsCache = BindingsCache(json, it) }
    }

    fun parseBindings(json: String?): Map<Char, GestureAction> {
        if (json.isNullOrEmpty()) return GestureAction.defaultQwertyBindings
        return runCatching {
            val obj = JSONObject(json)
            val result = mutableMapOf<Char, GestureAction>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNotEmpty()) {
                    val char = if (key.equals("space", ignoreCase = true) || key == " ") {
                        ' '
                    } else {
                        key[0].lowercaseChar()
                    }
                    val actionId = obj.getInt(key)
                    val action = GestureAction.fromId(actionId)
                    if (action != GestureAction.None) {
                        result[char] = action
                    }
                }
            }
            result
        }.getOrDefault(GestureAction.defaultQwertyBindings)
    }

    fun serializeBindings(map: Map<Char, GestureAction>): String {
        val obj = JSONObject()
        map.forEach { (char, action) ->
            if (action != GestureAction.None) {
                val key = if (char == ' ') "space" else char.lowercaseChar().toString()
                obj.put(key, action.id)
            }
        }
        return obj.toString()
    }
}
