import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder

fun main() {
    val q = "italian"
    var cuisine = "restaurant"
    if (q.contains("italian")) cuisine = "Italian restaurant"
    
    val urlStr = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(cuisine, "UTF-8")}&format=json&limit=3&viewbox=139.30,35.60,139.45,35.50&bounded=1"
    println("URL: $urlStr")
    val url = URL(urlStr)
    val connection = url.openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "GeminiNanoSample/1.0")
    connection.requestMethod = "GET"
    
    println("Response Code: ${connection.responseCode}")
    if (connection.responseCode == 200) {
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        println("Response: $response")
    } else {
        println("Error stream: ${connection.errorStream?.bufferedReader()?.use { it.readText() }}")
    }
}
