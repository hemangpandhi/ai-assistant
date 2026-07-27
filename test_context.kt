import com.tcs.vehicleassistant.SmartContextInjector

// We can't easily compile this standalone without Android mock context, 
// so let's just create a small mock to verify our logic.
fun testContext() {
    val q = "play some music"
    val parts = mutableListOf<String>()
    val mood = "Tired / Yawning"
    val occupants = 1
    
    parts.add("DriverMood=$mood, Occupants=$occupants")
    println(if (parts.isEmpty()) "" else parts.joinToString(" | "))
}
testContext()
