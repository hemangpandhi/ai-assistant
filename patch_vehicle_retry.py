import re

with open("app/src/main/java/com/example/gemininano/VehicleManager.kt", "r") as f:
    code = f.read()

old_func = """    suspend fun setPropertyVerified(propertyId: Int, targetAreaId: Int, value: String, dataType: String, timeoutMs: Long = 5000): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                val callback = object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(valueRecord: CarPropertyValue<*>) {
                        if (valueRecord.propertyId == propertyId && valueRecord.areaId == targetAreaId) {
                            val newValue = valueRecord.value
                            val matches = when (dataType.uppercase()) {
                                "INT" -> newValue == value.toFloatOrNull()?.toInt() ?: value.toIntOrNull()
                                "FLOAT" -> newValue == value.toFloatOrNull()
                                "BOOLEAN" -> newValue == value.toBoolean()
                                else -> newValue.toString() == value
                            }
                            if (matches) {
                                carPropertyManager?.unregisterCallback(this, propertyId)
                                if (continuation.isActive) continuation.resume(true)
                            }
                        }
                    }
                    override fun onErrorEvent(propId: Int, zone: Int) {
                        if (propId == propertyId && zone == targetAreaId) {
                            carPropertyManager?.unregisterCallback(this, propertyId)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }
                
                carPropertyManager?.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                
                try {
                    val writeSuccess = setGenericVhalProperty(propertyId, targetAreaId, value, dataType)
                    if (!writeSuccess) {
                        carPropertyManager?.unregisterCallback(callback, propertyId)
                        if (continuation.isActive) continuation.resume(false)
                    }
                } catch (e: Exception) {
                    carPropertyManager?.unregisterCallback(callback, propertyId)
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                continuation.invokeOnCancellation {
                    carPropertyManager?.unregisterCallback(callback, propertyId)
                }
            }
        } ?: false
    }"""

new_func = """    suspend fun setPropertyVerified(propertyId: Int, targetAreaId: Int, value: String, dataType: String, timeoutMs: Long = 1500, maxRetries: Int = 3): Boolean {
        var currentDelay = 500L
        repeat(maxRetries) { attempt ->
            val success = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                    val callback = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(valueRecord: CarPropertyValue<*>) {
                            if (valueRecord.propertyId == propertyId && valueRecord.areaId == targetAreaId) {
                                val newValue = valueRecord.value
                                val matches = when (dataType.uppercase()) {
                                    "INT" -> newValue == value.toFloatOrNull()?.toInt() ?: value.toIntOrNull()
                                    "FLOAT" -> newValue == value.toFloatOrNull()
                                    "BOOLEAN" -> newValue == value.toBoolean()
                                    else -> newValue.toString() == value
                                }
                                if (matches) {
                                    carPropertyManager?.unregisterCallback(this, propertyId)
                                    if (continuation.isActive) continuation.resume(true)
                                }
                            }
                        }
                        override fun onErrorEvent(propId: Int, zone: Int) {
                            if (propId == propertyId && zone == targetAreaId) {
                                carPropertyManager?.unregisterCallback(this, propertyId)
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                    
                    carPropertyManager?.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    
                    try {
                        val writeSuccess = setGenericVhalProperty(propertyId, targetAreaId, value, dataType)
                        if (!writeSuccess) {
                            carPropertyManager?.unregisterCallback(callback, propertyId)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    } catch (e: Exception) {
                        carPropertyManager?.unregisterCallback(callback, propertyId)
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    continuation.invokeOnCancellation {
                        carPropertyManager?.unregisterCallback(callback, propertyId)
                    }
                }
            } ?: false
            
            if (success) return true
            
            if (attempt < maxRetries - 1) {
                Log.w("VehicleManager", "Hardware command failed for property $propertyId. Retrying in ${currentDelay}ms (Attempt ${attempt + 1}/$maxRetries)...")
                kotlinx.coroutines.delay(currentDelay)
                currentDelay *= 2
            }
        }
        return false
    }"""

code = code.replace(old_func, new_func)

with open("app/src/main/java/com/example/gemininano/VehicleManager.kt", "w") as f:
    f.write(code)

print("VehicleManager.kt patched with Exponential Backoff Retry Engine.")
