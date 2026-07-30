package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the backend-selection policy that keeps the assistant usable on a device whose OpenCL driver
 * is missing or unloadable. Before the fallback chain existed, a GPU request on such a device left
 * LiteRT permanently uninitialized and every query failed.
 */
class GpuBackendResolverTest {

    private val gpu = AssistantConfig.Backend.GPU
    private val cpu = AssistantConfig.Backend.CPU
    private val npu = AssistantConfig.Backend.NPU
    private val auto = AssistantConfig.Backend.AUTO

    @Test
    fun `auto picks GPU when OpenCL is present`() {
        assertEquals(gpu, GpuBackendResolver.resolve(auto, openClAvailable = true))
    }

    @Test
    fun `auto falls back to CPU without OpenCL`() {
        assertEquals(cpu, GpuBackendResolver.resolve(auto, openClAvailable = false))
    }

    @Test
    fun `explicit GPU request is downgraded when OpenCL is missing`() {
        // Backend.GPU() cannot initialize without a driver, so honouring the request verbatim
        // would leave the engine dead rather than slow.
        assertEquals(cpu, GpuBackendResolver.resolve(gpu, openClAvailable = false))
    }

    @Test
    fun `explicit GPU request is honoured when OpenCL is present`() {
        assertEquals(gpu, GpuBackendResolver.resolve(gpu, openClAvailable = true))
    }

    @Test
    fun `explicit CPU request is never upgraded`() {
        assertEquals(cpu, GpuBackendResolver.resolve(cpu, openClAvailable = true))
        assertEquals(cpu, GpuBackendResolver.resolve(cpu, openClAvailable = false))
    }

    @Test
    fun `NPU request is honoured regardless of OpenCL`() {
        assertEquals(npu, GpuBackendResolver.resolve(npu, openClAvailable = true))
        assertEquals(npu, GpuBackendResolver.resolve(npu, openClAvailable = false))
    }

    @Test
    fun `unrecognised preference behaves like auto`() {
        assertEquals(gpu, GpuBackendResolver.resolve("Vulkan", openClAvailable = true))
        assertEquals(cpu, GpuBackendResolver.resolve("Vulkan", openClAvailable = false))
    }

    @Test
    fun `GPU chain degrades to CPU`() {
        assertEquals(listOf(gpu, cpu), GpuBackendResolver.fallbackChain(gpu, openClAvailable = true))
    }

    @Test
    fun `NPU chain tries GPU before CPU when OpenCL is present`() {
        assertEquals(listOf(npu, gpu, cpu), GpuBackendResolver.fallbackChain(npu, openClAvailable = true))
    }

    @Test
    fun `NPU chain skips GPU without OpenCL`() {
        assertEquals(listOf(npu, cpu), GpuBackendResolver.fallbackChain(npu, openClAvailable = false))
    }

    @Test
    fun `CPU chain does not repeat CPU`() {
        assertEquals(listOf(cpu), GpuBackendResolver.fallbackChain(cpu, openClAvailable = true))
    }

    @Test
    fun `every chain ends with CPU so initialization has a terminal fallback`() {
        val requests = listOf(auto, gpu, cpu, npu, "nonsense")
        for (request in requests) {
            for (openCl in listOf(true, false)) {
                val chain = GpuBackendResolver.fallbackChain(request, openCl)
                assertEquals(
                    "chain for request=$request openCl=$openCl must end with CPU, was $chain",
                    cpu,
                    chain.last()
                )
                assertEquals(
                    "chain for request=$request openCl=$openCl must not repeat a backend",
                    chain.size,
                    chain.distinct().size
                )
            }
        }
    }

    @Test
    fun `chain head always matches resolve`() {
        for (request in listOf(auto, gpu, cpu, npu)) {
            for (openCl in listOf(true, false)) {
                assertEquals(
                    GpuBackendResolver.resolve(request, openCl),
                    GpuBackendResolver.fallbackChain(request, openCl).first()
                )
            }
        }
    }
}
