package com.tcs.vehicleassistant.llm

import android.content.Context
import com.tcs.vehicleassistant.llm.LLMManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudLLMProviderTest {

    private lateinit var provider: CloudLLMProvider
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        provider = CloudLLMProvider()
        mockContext = mockk(relaxed = true)
        
        mockkObject(LLMManager)
        mockkObject(GeminiManager)
        mockkObject(AnthropicManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test initialization and state`() = runTest {
        assertFalse(provider.isReady())
        
        provider.initialize(mockContext, false)
        assertTrue(provider.isReady())
        
        provider.unload()
        assertFalse(provider.isReady())
        
        // This should not crash or do anything
        provider.resetConversation()
    }

    @Test
    fun `test generateStream routes to GeminiManager when model contains Gemini`() = runTest {
        LLMManager.currentCloudModelName = "Gemini Pro"
        
        val callbackSlot = slot<CloudMessageCallback>()
        coEvery { GeminiManager.sendMessageAsync(any(), any(), capture(callbackSlot)) } returns Unit
        
        var receivedToken = ""
        var doneResponse = ""
        
        provider.generateStream(
            context = mockContext,
            prompt = "System Prompt",
            userQuery = "Hello",
            onToken = { receivedToken = it },
            onDone = { text, _ -> doneResponse = text },
            onError = {}
        )
        
        coVerify(exactly = 1) { GeminiManager.sendMessageAsync("System Prompt", "Hello", any()) }
        
        // Simulate callback
        callbackSlot.captured.onMessage("Token1")
        assertEquals("Token1", receivedToken)
        
        callbackSlot.captured.onDone()
        assertEquals("Token1", doneResponse)
    }
    
    @Test
    fun `test generateStream routes to AnthropicManager otherwise`() = runTest {
        LLMManager.currentCloudModelName = "Claude 3.5 Sonnet"
        
        val callbackSlot = slot<CloudMessageCallback>()
        coEvery { AnthropicManager.sendMessageAsync(any(), any(), capture(callbackSlot)) } returns Unit
        
        var errorThrown = false
        
        provider.generateStream(
            context = mockContext,
            prompt = "System Prompt",
            userQuery = "Hello",
            onToken = {},
            onDone = { text, _ -> },
            onError = { errorThrown = true }
        )
        
        coVerify(exactly = 1) { AnthropicManager.sendMessageAsync("System Prompt", "Hello", any()) }
        
        // Simulate error callback
        callbackSlot.captured.onError(RuntimeException("Test Exception"))
        assertTrue(errorThrown)
    }
}
