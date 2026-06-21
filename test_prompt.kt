// A quick test script to estimate token size
import java.io.File
import org.json.JSONObject

fun main() {
    val file = File("app/src/main/assets/vehicle_skills_registry_2.json")
    val content = file.readText()
    val tools = JSONObject(content).getJSONArray("tools")
    println("Total tools: ${tools.length()}")
}
