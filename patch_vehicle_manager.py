import re

with open("app/src/main/java/com/example/gemininano/VehicleManager.kt", "r") as f:
    content = f.read()

old_func = """    suspend fun setPropertyVerified(propertyId: Int, targetAreaId: Int, value: String, dataType: String, timeoutMs: Long = 1500, maxRetries: Int = 3): Boolean {
        var currentDelay = 500L
        repeat(maxRetries) { attempt ->"""

new_func = """    suspend fun setPropertyVerified(propertyId: Int, targetAreaId: Int, value: String, dataType: String, timeoutMs: Long = 1500, maxRetries: Int = 3): Boolean {
        // Pre-check if the value is already set to avoid VHAL timeout (VHAL doesn't fire onChange if value didn't change)
        try {
            val currentValue = when (dataType.uppercase()) {
                "INT" -> carPropertyManager?.getIntProperty(propertyId, targetAreaId)?.toString()
                "FLOAT" -> carPropertyManager?.getFloatProperty(propertyId, targetAreaId)?.toString()
                "BOOLEAN" -> carPropertyManager?.getBooleanProperty(propertyId, targetAreaId)?.toString()
                "STRING" -> carPropertyManager?.getProperty<Any>(Any::class.java, propertyId, targetAreaId)?.value?.toString()
                else -> null
            }
            
            val matches = when (dataType.uppercase()) {
                "INT" -> currentValue?.toFloatOrNull()?.toInt() == value.toFloatOrNull()?.toInt()
                "FLOAT" -> currentValue?.toFloatOrNull() == value.toFloatOrNull()
                "BOOLEAN" -> currentValue?.toBoolean() == value.toBoolean()
                else -> currentValue == value
            }
            
            if (matches) {
                Log.d("VehicleManager", "Property $propertyId area $targetAreaId is already $value. Skipping write.")
                return true
            }
        } catch (e: Exception) {
            Log.w("VehicleManager", "Failed pre-check for property $propertyId", e)
        }

        var currentDelay = 500L
        repeat(maxRetries) { attempt ->"""

content = content.replace(old_func, new_func)

with open("app/src/main/java/com/example/gemininano/VehicleManager.kt", "w") as f:
    f.write(content)

print("Applied patch_vehicle_manager.py successfully!")
