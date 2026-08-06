package com.tcs.vehicleassistant

import org.junit.Test
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

class KwsTest {
    @Test
    fun testKwsApi() {
        val clazz = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
        println("METHODS:")
        clazz.methods.forEach { println(it) }
        
        val streamClazz = Class.forName("com.k2fsa.sherpa.onnx.OnlineStream")
        println("STREAM METHODS:")
        streamClazz.methods.forEach { println(it) }
        
        val resultClazz = Class.forName("com.k2fsa.sherpa.onnx.KeywordResult")
        println("RESULT METHODS:")
        resultClazz.methods.forEach { println(it) }
    }
}
