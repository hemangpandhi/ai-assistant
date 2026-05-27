import re

with open('app/src/main/java/com/example/gemininano/LocalLLMActivity.kt', 'r') as f:
    content = f.read()

# Remove MockVehicleState definition and vehicleState
content = re.sub(r'    data class MockVehicleState\(.*?\n    \)\n    private val vehicleState = MockVehicleState\(\)\n', '', content, flags=re.DOTALL)

# Remove carPropertyManager
content = re.sub(r'    private var carPropertyManager: CarPropertyManager\? = null\n', '', content)

# Replace onCreate init
init_pattern = r'        try \{\n            val car = Car\.createCar\(this\)\n            carPropertyManager = car\.getCarManager\(Car\.PROPERTY_SERVICE\) as CarPropertyManager\n        \} catch \(e: Exception\) \{\n            Log\.e\("LocalLLMActivity", "Failed to initialize CarPropertyManager", e\)\n        \}'
content = re.sub(init_pattern, '        VehicleManager.initialize(this)', content)

# Replace getRealTemperature definition
content = re.sub(r'    private fun getRealTemperature\(\): Int \{.*?\n    \}\n', '', content, flags=re.DOTALL)

# Replace setRealTemperature definition
content = re.sub(r'    private fun setRealTemperature\(temp: Float\) \{.*?\n    \}\n', '', content, flags=re.DOTALL)

# Replace usages
content = content.replace('getRealTemperature()', 'VehicleManager.getRealTemperature()')
content = content.replace('vehicleState.', 'VehicleManager.vehicleState.')
content = content.replace('setRealTemperature', 'VehicleManager.writeTemperatureToVhal')

with open('app/src/main/java/com/example/gemininano/LocalLLMActivity.kt', 'w') as f:
    f.write(content)
