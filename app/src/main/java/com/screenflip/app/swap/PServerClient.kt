package com.screenflip.app.swap

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Minimal client for the AYN Thor / Odin firmware "PServerBinder" vendor
 * service (reverse-engineered protocol):
 *
 * - Service name: "PServerBinder"
 * - Transaction code: 0
 * - Request parcel: `writeStringArray(arrayOf(command, waitFlag))` where
 *   waitFlag is "1" (wait for completion) or "0"
 * - Reply parcel: UTF-8 bytes of stdout via `createByteArray()`
 */
object PServerClient {

    private const val TAG = "PServerClient"
    private const val SERVICE_NAME = "PServerBinder"

    @Volatile
    private var cachedBinder: IBinder? = null

    /**
     * Resolves the vendor binder via reflection into the hidden
     * [android.os.ServiceManager]. Returns null when the service is not
     * present (e.g. non-Odin firmware) or the call fails.
     */
    fun getBinder(): IBinder? {
        val current = cachedBinder
        if (current != null && current.isBinderAlive) {
            return current
        }
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, SERVICE_NAME) as? IBinder
            if (binder != null) {
                cachedBinder = binder
            }
            binder
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to obtain $SERVICE_NAME binder", t)
            cachedBinder = null
            null
        }
    }

    fun isAvailable(): Boolean = getBinder() != null

    /**
     * Executes a shell command through the vendor service.
     *
     * @param wait when true the call blocks until the command finishes and
     *        stdout is returned; when false the command is fire-and-forget.
     */
    fun exec(command: String, wait: Boolean = true): Result<String> {
        val binder = getBinder()
            ?: return Result.failure(IllegalStateException("$SERVICE_NAME is not available"))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, if (wait) "1" else "0"))
            binder.transact(0, data, reply, 0)
            val bytes = reply.createByteArray()
            val output = if (bytes != null) {
                String(bytes, Charsets.UTF_8).trimEnd()
            } else {
                ""
            }
            Result.success(output)
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing command via $SERVICE_NAME", t)
            Result.failure(t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * [exec] with a hard timeout: the transact runs on a worker thread and is
     * abandoned after [timeoutMs] (guards against the vendor service hanging
     * on malformed/truncated commands).
     */
    fun execWithTimeout(command: String, wait: Boolean, timeoutMs: Long): Result<String> {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "psrv-timeout").apply { isDaemon = true }
        }
        val future: java.util.concurrent.Future<Result<String>> =
            executor.submit(java.util.concurrent.Callable { exec(command, wait) })
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            Log.e(TAG, "PServer exec timed out after ${timeoutMs}ms: $command")
            Result.failure(t)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            executor.shutdownNow()
        }
    }
}
