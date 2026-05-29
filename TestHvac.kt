fun main() {
    var currentTemperature = 22f

    fun getRealTemperature(): Int {
        return if (currentTemperature < 50f) {
            ((currentTemperature * 9f / 5f) + 32f).toInt()
        } else {
            currentTemperature.toInt()
        }
    }

    fun writeTemperatureToVhal(temp: Float) {
        var finalTemp = temp
        if (finalTemp > 30f) {
            finalTemp = (finalTemp - 32f) * 5f / 9f
        }
        finalTemp = Math.round(finalTemp * 2.0f) / 2.0f
        
        println("VHAL set to: $finalTemp C")
        currentTemperature = temp
    }

    println("Start temp: ${getRealTemperature()} F")
    
    val currentTemp = getRealTemperature().toDouble()
    writeTemperatureToVhal((currentTemp + 5).toFloat())
    println("After increase 5, temp is: ${getRealTemperature()} F")

    val currentTemp2 = getRealTemperature().toDouble()
    writeTemperatureToVhal((currentTemp2 - 10).toFloat())
    println("After decrease 10, temp is: ${getRealTemperature()} F")
}
