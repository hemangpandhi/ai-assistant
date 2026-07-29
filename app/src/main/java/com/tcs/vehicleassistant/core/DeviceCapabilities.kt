package com.tcs.vehicleassistant.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Probes the accelerators LiteRT-LM can actually reach on this device.
 *
 * LiteRT's GPU backend `dlopen`s a vendor OpenCL driver at runtime; if none is present the engine
 * fails to initialize. Previously the app requested `Backend.GPU()` unconditionally and had no
 * fallback, so a device without a usable OpenCL driver left the assistant permanently unusable.
 * This class lets [com.tcs.vehicleassistant.LLMManager] pick a backend it can actually run on.
 *
 * The candidate library names below mirror the `dlopen` targets found in `liblitertlm_jni.so`.
 */
object DeviceCapabilities {

    private const val TAG = "DeviceCapabilities"

    /** OpenCL driver sonames LiteRT attempts, in the order it tries them. */
    private val OPENCL_LIBRARY_NAMES = listOf(
        "libOpenCL.so",
        "libOpenCL-pixel.so",
        "libOpenCL-car.so",
        "libGLES_mali.so",
    )

    /** Vendor library search roots on a 64-bit Android/AAOS image. */
    private val NATIVE_LIBRARY_DIRS = listOf(
        "/vendor/lib64",
        "/vendor/lib64/egl",
        "/system/vendor/lib64",
        "/system/vendor/lib64/egl",
        "/system/lib64",
        "/odm/lib64",
        "/apex/com.android.vndk.v34/lib64",
    )

    /** Devices whose GPU is known to drive the LiteRT OpenCL backend well. */
    private val KNOWN_OPENCL_DEVICES = setOf(
        "tangorpro", // Pixel Tablet (Tensor G2, Mali-G710)
        "felix",
        "cheetah",
        "panther",
        "lynx",
        "husky",
        "shiba",
    )

    @Volatile
    private var cachedOpenClPath: String? = null

    @Volatile
    private var openClProbed = false

    /**
     * Absolute path of the first vendor OpenCL driver present on the device, or `null` if the GPU
     * backend cannot be loaded. Result is cached because it only depends on the system image.
     */
    fun openClLibraryPath(): String? {
        if (openClProbed) return cachedOpenClPath
        synchronized(this) {
            if (openClProbed) return cachedOpenClPath
            cachedOpenClPath = findOpenClLibrary()
            openClProbed = true
            if (cachedOpenClPath == null) {
                Log.w(TAG, "No vendor OpenCL driver found; LiteRT GPU backend is unavailable.")
            } else {
                Log.i(TAG, "OpenCL driver available at $cachedOpenClPath")
            }
            return cachedOpenClPath
        }
    }

    private fun findOpenClLibrary(): String? {
        for (dir in NATIVE_LIBRARY_DIRS) {
            for (name in OPENCL_LIBRARY_NAMES) {
                val candidate = File(dir, name)
                val readable = try {
                    candidate.exists()
                } catch (e: SecurityException) {
                    Log.d(TAG, "Cannot stat ${candidate.path}: ${e.message}")
                    false
                }
                if (readable) return candidate.absolutePath
            }
        }
        return null
    }

    /** True when a vendor OpenCL driver is present, meaning `Backend.GPU()` can initialize. */
    fun hasOpenCl(): Boolean = openClLibraryPath() != null

    /** True on the Pixel Tablet, whose Mali-G710 is a validated OpenCL target for this app. */
    fun isPixelTablet(): Boolean =
        Build.DEVICE.equals("tangorpro", ignoreCase = true) ||
            Build.PRODUCT.contains("tangorpro", ignoreCase = true)

    /** True on any device with a validated OpenCL driver profile. */
    fun isKnownOpenClDevice(): Boolean =
        KNOWN_OPENCL_DEVICES.any { Build.DEVICE.equals(it, ignoreCase = true) }

    /** True for tablets and large head units, which keep the model resident between sessions. */
    fun isLargeScreen(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= AssistantConfig.LARGE_SCREEN_MIN_WIDTH_DP ||
            isPixelTablet()

    /** Physical CPU cores, used to size the CPU fallback thread pool. */
    fun cpuCoreCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Resolves the backend LiteRT should be configured with.
     *
     * `"Auto"` used to silently mean `"GPU"` regardless of hardware. It now consults the OpenCL
     * probe, and an explicit GPU request on a device with no OpenCL driver is downgraded to CPU
     * instead of failing initialization.
     */
    fun resolveBackend(requested: String): String = GpuBackendResolver.resolve(
        requested = requested,
        openClAvailable = hasOpenCl(),
    )

    /**
     * Backends to try in order for [requested], so a GPU driver that loads but fails to compile
     * kernels still ends with a working assistant.
     */
    fun backendFallbackChain(requested: String): List<String> = GpuBackendResolver.fallbackChain(
        requested = requested,
        openClAvailable = hasOpenCl(),
    )

    /** Human-readable accelerator summary for diagnostics screens and bug reports. */
    fun describe(context: Context): String = buildString {
        append("device=${Build.DEVICE} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        append(" cores=${cpuCoreCount()}")
        append(" largeScreen=${isLargeScreen(context)}")
        append(" openCL=${openClLibraryPath() ?: "unavailable"}")
    }
}
