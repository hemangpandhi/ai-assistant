fun main() {
    val regex = Regex(".*\\b(driver|passenger|temperature|hot|cold|increase|decrease).*\\b.*", RegexOption.IGNORE_CASE)
    println(regex.matches("drivers"))
    println(regex.matches("increasing"))
}
