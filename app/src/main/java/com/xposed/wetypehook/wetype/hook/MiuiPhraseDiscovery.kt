package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.AtomicFile
import android.widget.PopupWindow
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.loadClassOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest

/** Update-aware discovery: strong log evidence plus runtime contracts, never first-match. */
internal object MiuiPhraseDiscovery {
    private const val TARGET = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val RULE_VERSION = 2
    data class Target(val clazz: Class<*>, val showMethods: List<Method>, val source: String)

    fun resolve(loader: ClassLoader, dexPath: String?): Target? {
        val context = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
        val paths = (dexPath?.split(File.pathSeparator)?.filter { File(it).isFile }?.takeIf { it.isNotEmpty() }
            ?: context?.let { runCatching { listOf(it.packageManager.getApplicationInfo("com.miui.phrase", 0).sourceDir) }.getOrNull() })
            .orEmpty().distinct().sorted()
        val fingerprint = runCatching {
            val hash = MessageDigest.getInstance("SHA-256")
            paths.forEach { path ->
                hash.update(path.toByteArray())
                File(path).inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                }
            }
            if (paths.isEmpty()) null else hash.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
        val cache = context?.let { AtomicFile(File(it.cacheDir, "miui-phrase-discovery.json")) }
        fun save(target: Target): Target {
            if (cache != null && fingerprint != null) runCatching {
                val data = JSONObject().put("rule", RULE_VERSION).put("fingerprint", fingerprint)
                    .put("class", target.clazz.name).put("methods", JSONArray(target.showMethods.map { it.name }.distinct()))
                val output = cache.startWrite()
                try { output.write(data.toString().toByteArray()); cache.finishWrite(output) }
                catch (error: Throwable) { cache.failWrite(output); throw error }
            }.onFailure { Log.i("MIUI clipboard discovery cache write skipped: ${it.message}") }
            return target
        }
        // Known API is always preferred and validated, even if the APK has changed.
        loadClassOrNull(TARGET, loader)?.let { clazz ->
            val methods = clazz.declaredMethods.filter { it.name == "initPopupWindow" && validShow(it) }
            if (validClass(clazz) && methods.isNotEmpty()) return save(Target(clazz, methods, "known API"))
        }
        if (fingerprint != null && cache != null) runCatching {
            val data = JSONObject(cache.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            if (data.getInt("rule") != RULE_VERSION || data.getString("fingerprint") != fingerprint) return@runCatching null
            val clazz = loadClassOrNull(data.getString("class"), loader) ?: return@runCatching null
            val names = data.getJSONArray("methods").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            val methods = clazz.declaredMethods.filter { it.name in names && validShow(it) }
            if (validClass(clazz) && names.isNotEmpty() && methods.map { it.name }.toSet() == names)
                Target(clazz, methods, "fingerprint cache") else null
        }.getOrNull()?.let { return it }
        val candidates = runCatching {
            System.loadLibrary("dexkit")
            paths.flatMap { path ->
                DexKitBridge.create(path).use { bridge ->
                    bridge.findMethod {
                        matcher { usingStrings("initPopupWindow: token invalidated") }
                    }.mapNotNull { data -> runCatching { data.getMethodInstance(loader) }.getOrNull() }
                }
            }.filter { validShow(it) && validClass(it.declaringClass) }.distinct()
        }.onFailure { Log.i("MIUI clipboard discovery failed: ${it.message}") }.getOrDefault(emptyList())
        val clazz = candidates.map { it.declaringClass }.distinct().singleOrNull() ?: run {
            Log.i("MIUI clipboard discovery rejected: ${candidates.size} matching methods, no unique popup; preserving system style")
            return null
        }
        return save(Target(clazz, candidates.filter { it.declaringClass == clazz }, "DexKit signature"))
    }

    private fun validShow(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            matchesMiuiPopupShowSignature(method.parameterTypes.map { it.name })

    private fun validClass(clazz: Class<*>): Boolean =
        PopupWindow::class.java.isAssignableFrom(clazz) && clazz != PopupWindow::class.java &&
            generateSequence(clazz) { it.superclass }.any { owner ->
                owner.declaredFields.any { !Modifier.isStatic(it.modifiers) && InputMethodService::class.java.isAssignableFrom(it.type) }
            }
}

internal fun matchesMiuiPopupShowSignature(parameters: List<String>): Boolean =
    parameters.size in 5..7 && parameters.take(5).all { it == "int" } &&
        parameters.drop(5).all { it == "boolean" }
