package com.xposed.wetypehook

import android.app.Activity
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 微信输入法进程重启器：设置保存后调用，让 Hook 在新进程里读到最新配置。
 *
 * 双通道（只杀 `:hld` 输入法进程，主进程保留，系统会自动重新拉起输入法服务）：
 * 1. 优先发显式有序广播，由跑在输入法进程内（同 uid）的
 *    [com.xposed.wetypehook.wetype.hook.WeTypeImeRestart] 接收器下发 kill。
 * 2. 未取得回执时才尝试 Root 通道。
 * 无回执或超时表示无法确认，不能据此认定模块未生效或未授予 Root。
 */
object ImeRestarter {

    const val WETYPE_PACKAGE = "com.tencent.wetype"
    const val ACTION_RESTART_IME = "com.xposed.wetypehook.action.RESTART_IME"

    private const val IME_PROCESS_NAME = "com.tencent.wetype:hld"
    private const val RESULT_TIMEOUT_MILLIS = 8000L
    private const val SU_TIMEOUT_SECONDS = 10L
    private const val ROOT_PROBE_TIMEOUT_SECONDS = 8L

    sealed interface Result {
        data object Restarted : Result
        data object Unconfirmed : Result
        data object Failed : Result
        data object NotRunning : Result
    }

    suspend fun restartWeType(context: Context): Result {
        android.util.Log.i("WeTypeImeRestart", "restart requested from ${context.packageName}")
        val broadcast = restartViaBroadcast(context)
        android.util.Log.i("WeTypeImeRestart", "broadcast result=$broadcast")
        if (broadcast != Result.Unconfirmed) return broadcast
        val rootGranted = withContext(Dispatchers.IO) { hasRoot() }
        return if (rootGranted) rootKillIme() else Result.Unconfirmed
    }

    private suspend fun rootKillIme(): Result = withContext(Dispatchers.IO) {
        val pids = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps -A -o PID,NAME"))
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runCatching { process.destroyForcibly() }
                return@withContext Result.Unconfirmed
            }
            if (process.exitValue() != 0) return@withContext Result.Unconfirmed
            process.inputStream.bufferedReader().readText().lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == IME_PROCESS_NAME) {
                    parts[0].toIntOrNull()?.takeIf { it > 0 }
                } else {
                    null
                }
            }
        }.getOrNull() ?: return@withContext Result.Unconfirmed
        if (pids.isEmpty()) {
            android.util.Log.i("WeTypeImeRestart", "Root process query confirmed IME is not running")
            return@withContext Result.NotRunning
        }
        val killed = runCatching {
            val kill = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "kill -9 ${pids.joinToString(" ")}"))
            if (!kill.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runCatching { kill.destroyForcibly() }
                return@withContext Result.Unconfirmed
            }
            kill.exitValue() == 0
        }.getOrDefault(false)
        if (killed) Result.Restarted else Result.Unconfirmed
    }

    private suspend fun restartViaBroadcast(context: Context): Result {
        val appContext = context.applicationContext ?: context
        val installed = runCatching {
            appContext.packageManager.getLaunchIntentForPackage(WETYPE_PACKAGE)
        }.getOrNull() != null
        if (!installed) {
            android.util.Log.w("WeTypeImeRestart", "WeType launch intent unavailable to ${appContext.packageName}")
            return Result.Unconfirmed
        }
        return withTimeoutOrNull(RESULT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val resultReceiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        android.util.Log.i("WeTypeImeRestart", "restart reply code=$resultCode data=$resultData active=${continuation.isActive}")
                        if (!continuation.isActive) return
                        if (resultCode == Activity.RESULT_OK && resultData in setOf("killed", "scheduled")) {
                            continuation.resume(Result.Restarted) {}
                        } else if (resultData.isNullOrEmpty()) {
                            continuation.resume(Result.Unconfirmed) {}
                        } else {
                            continuation.resume(Result.Failed) {}
                        }
                    }
                }
                val delivered = runCatching {
                    val intent = Intent(ACTION_RESTART_IME).setPackage(WETYPE_PACKAGE)
                    val handler = Handler(Looper.getMainLooper())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // The receiver verifies sentFromUid; it is hidden unless identity is shared.
                        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                        appContext.sendOrderedBroadcast(intent, null, options, resultReceiver, handler,
                            Activity.RESULT_CANCELED, null, null)
                    } else {
                        appContext.sendOrderedBroadcast(intent, null, resultReceiver, handler,
                            Activity.RESULT_CANCELED, null, null)
                    }
                }.onFailure { android.util.Log.w("WeTypeImeRestart", "restart broadcast failed", it) }.isSuccess
                if (!delivered && continuation.isActive) {
                    continuation.resume(Result.Failed) {}
                }
            }
        } ?: Result.Unconfirmed
    }

    private fun hasRoot(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        if (!process.waitFor(ROOT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { process.destroyForcibly() }
            return false
        }
        if (process.exitValue() != 0) return false
        val output = process.inputStream.bufferedReader().readText()
        output.contains("uid=0")
    }.getOrDefault(false)
}
