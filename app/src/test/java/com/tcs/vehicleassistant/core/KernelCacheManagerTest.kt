package com.tcs.vehicleassistant.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the OpenCL kernel cache lifecycle. Compiled kernels are the expensive part of a cold start
 * on the GPU backend, and they are only valid for the model and backend they were built against --
 * reusing them across either produces a native crash inside the LiteRT GPU delegate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KernelCacheManagerTest {

    private lateinit var context: Context
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        KernelCacheManager.invalidate(context)

        modelFile = File(context.filesDir, "model-a.litertlm")
        modelFile.writeText("model-a")
    }

    private fun writeKernel(dir: String, name: String = "kernel.bin") =
        File(dir, name).apply { parentFile?.mkdirs(); writeText("compiled-kernel") }

    @Test
    fun `prepare returns a directory that exists`() {
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        assertTrue("$dir must exist", File(dir).isDirectory)
    }

    @Test
    fun `the cache lives outside the evictable cache dir`() {
        // Android reclaims getCacheDir() under storage pressure, which silently costs a full
        // kernel recompile on the next launch.
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        assertFalse(
            "kernel cache must not sit under cacheDir, was $dir",
            dir.startsWith(context.cacheDir.absolutePath)
        )
    }

    @Test
    fun `the cache lives outside backup-eligible storage`() {
        // Restoring kernels onto a device with a different GPU would hand LiteRT invalid binaries.
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        assertTrue(
            "kernel cache must sit under noBackupFilesDir, was $dir",
            dir.startsWith(context.noBackupFilesDir.absolutePath)
        )
    }

    @Test
    fun `repeated prepare calls for the same model keep the cached kernels`() {
        val first = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        writeKernel(first)

        val second = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        assertEquals(first, second)
        assertTrue("a warm cache must survive re-preparation", KernelCacheManager.isWarm(context))
    }

    @Test
    fun `switching model discards the cache`() {
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        writeKernel(dir)
        assertTrue(KernelCacheManager.isWarm(context))

        val otherModel = File(context.filesDir, "model-b.litertlm").apply { writeText("model-b") }
        KernelCacheManager.prepare(context, otherModel.path, AssistantConfig.Backend.GPU)

        assertFalse("kernels compiled for another model must be discarded", KernelCacheManager.isWarm(context))
    }

    @Test
    fun `switching backend discards the cache`() {
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        writeKernel(dir)
        assertTrue(KernelCacheManager.isWarm(context))

        KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.CPU)

        assertFalse("kernels compiled for another backend must be discarded", KernelCacheManager.isWarm(context))
    }

    @Test
    fun `rewriting the model file discards the cache`() {
        // Same path, different content: the fingerprint includes size and mtime so a re-downloaded
        // or swapped model does not reuse kernels built for the previous bytes.
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        writeKernel(dir)

        modelFile.writeText("model-a-but-longer-content")
        modelFile.setLastModified(modelFile.lastModified() + 10_000)
        KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)

        assertFalse(KernelCacheManager.isWarm(context))
    }

    @Test
    fun `invalidate clears the kernels and the recorded fingerprint`() {
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        writeKernel(dir)

        KernelCacheManager.invalidate(context)

        assertFalse(KernelCacheManager.isWarm(context))
        assertEquals(0L, KernelCacheManager.sizeBytes(context))
        assertEquals(
            null,
            context.getSharedPreferences(AssistantConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(AssistantConfig.Prefs.KERNEL_CACHE_MODEL, null)
        )
    }

    @Test
    fun `invalidate keeps the directory usable for the next compile`() {
        KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        KernelCacheManager.invalidate(context)

        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        assertTrue(File(dir).isDirectory)
    }

    @Test
    fun `invalidate on a cold cache is harmless`() {
        KernelCacheManager.invalidate(context)
        KernelCacheManager.invalidate(context)
        assertFalse(KernelCacheManager.isWarm(context))
    }

    @Test
    fun `an empty cache reports cold`() {
        KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        assertFalse("an empty directory is not a warm cache", KernelCacheManager.isWarm(context))
    }

    @Test
    fun `sizeBytes sums nested kernel files`() {
        val dir = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        writeKernel(dir, "a.bin")
        writeKernel(dir, "nested/b.bin")

        assertEquals(
            "compiled-kernel".length * 2L,
            KernelCacheManager.sizeBytes(context)
        )
    }

    @Test
    fun `an absent model still yields a usable cache directory`() {
        val dir = KernelCacheManager.prepare(
            context,
            File(context.filesDir, "not-downloaded-yet.litertlm").path,
            AssistantConfig.Backend.GPU
        )
        assertTrue(File(dir).isDirectory)
    }

    @Test
    fun `the cache directory is stable across models`() {
        val a = KernelCacheManager.prepare(context, modelFile.path, AssistantConfig.Backend.GPU)
        val other = File(context.filesDir, "model-b.litertlm").apply { writeText("b") }
        val b = KernelCacheManager.prepare(context, other.path, AssistantConfig.Backend.GPU)

        assertEquals("the path is fixed; only its contents are invalidated", a, b)
        assertNotEquals("", a)
    }
}
