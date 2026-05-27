import re

with open('app/src/main/java/com/example/gemininano/LocalLLMActivity.kt', 'r') as f:
    content = f.read()

prompt_pattern = r'return "You are a concise in-car AI assistant\.\\n"\s*\+\s*"Current state:.*?Assistant:"'
new_prompt = """return "You are a concise in-car AI assistant.\\n" +
               "Current state: Speed ${VehicleManager.getRealSpeed()}mph, Cabin Temp ${VehicleManager.getRealTemperature()}F, Heater ${VehicleManager.getRealSeatHeaterLevel()}, Fuel Level ${VehicleManager.getFuelLevel()}%, Gear ${VehicleManager.getGearSelection()}.\\n" +
               "RULES:\\n" +
               "1. Keep answers under 15 words.\\n" +
               "2. If user asks to increase temp, output EXACTLY <TEMP_UP> and a short confirmation.\\n" +
               "3. If user asks to decrease temp, output EXACTLY <TEMP_DOWN> and a short confirmation.\\n" +
               "4. If user asks to defrost windshield, output EXACTLY <DEFROST_ON> and a short confirmation.\\n" +
               "5. If Gear is Drive, refuse any request that distracts the driver (like playing video/movies) for safety.\\n" +
               "6. Be direct, no fluff.\\n" +
               "User: '$userInput'\\nAssistant:\""""
content = re.sub(prompt_pattern, new_prompt, content, flags=re.DOTALL)

defrost_logic = """
                    if (lastResponseBuilder.contains("<TEMP_DOWN>")) {
                        val newTemp = VehicleManager.getRealTemperature() - 4
                        VehicleManager.writeTemperatureToVhal(newTemp.toFloat())
                        didUpdateTemp = true
                        val idx = lastResponseBuilder.indexOf("<TEMP_DOWN>")
                        lastResponseBuilder.delete(idx, idx + 11)
                    }
                    if (lastResponseBuilder.contains("<DEFROST_ON>")) {
                        VehicleManager.writeDefrosterToVhal(true)
                        val idx = lastResponseBuilder.indexOf("<DEFROST_ON>")
                        lastResponseBuilder.delete(idx, idx + 12)
                    }"""
                    
content = content.replace("""
                    if (lastResponseBuilder.contains("<TEMP_DOWN>")) {
                        val newTemp = VehicleManager.getRealTemperature() - 4
                        VehicleManager.writeTemperatureToVhal(newTemp.toFloat())
                        didUpdateTemp = true
                        val idx = lastResponseBuilder.indexOf("<TEMP_DOWN>")
                        lastResponseBuilder.delete(idx, idx + 11)
                    }""", defrost_logic)

with open('app/src/main/java/com/example/gemininano/LocalLLMActivity.kt', 'w') as f:
    f.write(content)
