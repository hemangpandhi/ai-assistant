package com.tcs.vehicleassistant.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the directory LiteRT uses to persist serialized inference contexts — on the GPU backend
 * this is where compiled OpenCL kernels land.
 *
 * The engine previously pointed at [Context.getCacheDir], which Android evicts under storage
 * pressure. Losing the cache forces a full OpenCL kernel recompile on the next cold start, which
 * is the slowest part of bringing the model up. This uses no-backup internal storage instead, so
 * the kernels survive reboots and low-storage cleanups but are never copied off the device.
 *
 * Kernels are only valid for the model they were compiled against, so the cache is dropped
 * whenever the model path or backend changes.
 */
object KernelCacheManager {

    private const val TAG = "KernelCacheManager"

    /**
     * Directory to hand LiteRT as `EngineConfig.cacheDir`, cleared first if the previous contents
     * were compiled for a different model or backend.
     */
    fun prepare(context: Context, modelPath: String, backend: String): String {
        val root = cacheRoot(context)
        val prefs = context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val fingerprint = fingerprint(modelPath, backend)
        val previous = prefs.getString(AssistantConfig.Prefs.KERNEL_CACHE_MODEL, null)

        if (previous != null && previous != fingerprint) {
            Log.i(TAG, "Model/backend changed; discarding stale kernel cache at ${root.path}")
            clearDirectory(root)
        }

        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "Could not create ${root.path}; falling back to the volatile cache dir")
            return context.cacheDir.absolutePath
        }

        prefs.edit().putString(AssistantConfig.Prefs.KERNEL_CACHE_MODEL, fingerprint).apply()
        return root.absolutePath
    }

    /** Drops every cached kernel, forcing a recompile on the next engine initialization. */
    fun invalidate(context: Context) {
        clearDirectory(cacheRoot(context))
        context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(AssistantConfig.Prefs.KERNEL_CACHE_MODEL)
            .apply()
    }

    /** True once LiteRT has written a serialized context, meaning the next start skips compilation. */
    fun isWarm(context: Context): Boolean {
        val root = cacheRoot(context)
        return root.isDirectory && (root.listFiles()?.isNotEmpty() == true)
    }

    /** Total bytes held by the cache, surfaced on the diagnostics screen. */
    fun sizeBytes(context: Context): Long {
        val root = cacheRoot(context)
        if (!root.isDirectory) return 0L
        return root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private fun cacheRoot(context: Context): File {
        // noBackupFilesDir keeps device-specific compiled kernels out of cloud/adb backups, where
        // they would be restored onto hardware with a different GPU.
        val parent = try {
            context.noBackupFilesDir ?: context.filesDir
        } catch (e: Exception) {
            Log.w(TAG, "noBackupFilesDir unavailable, using filesDir", e)
            context.filesDir
        }
        return File(parent, AssistantConfig.Llm.KERNEL_CACHE_DIR)
    }

    private fun fingerprint(modelPath: String, backend: String): String {
        val model = File(modelPath)
        val stamp = if (model.exists()) "${model.length()}:${model.lastModified()}" else "absent"
        return "$modelPath|$backend|$stamp"
    }

    private fun clearDirectory(dir: File) {
        if (!dir.exists()) return
        try {
            dir.walkBottomUp().forEach { if (it != dir) it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear kernel cache at ${dir.path}", e)
        }
    }
}
