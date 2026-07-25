fun main() {
    val text = "I found these options nearby: 1. Sensō-ji Temple, 2. Tokyo Skytree, 3. Meiji Shrine. Which one?"
    val map = linkedMapOf<Int, String>()
    val patterns = listOf(
        Regex("""(?:\b|(?<=[:,;\n\s]))(\d+)\.\s*([^,\n;.]+)"""),
        Regex("""(?:\b|(?<=[:,;\n\s]))(\d+)\)\s*([^,\n;.]+)""")
    )
    for (pattern in patterns) {
        for (match in pattern.findAll(text)) {
            val num = match.groupValues[1].toIntOrNull() ?: continue
            val name = match.groupValues[2].trim()
            if (name.isNotBlank()) map[num] = name
        }
    }
    println(map)
}
