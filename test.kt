fun main() {
    val q = "places to visit in tokyo"
    val isGenericFood = (q.contains("hungry") || q.contains("food")) && !(q.contains("italian") || q.contains("mexican") || q.contains("pizza") || q.contains("burger") || q.contains("sushi") || q.contains("vegetarian") || q.contains("vegan") || q.contains("indian") || q.contains("thai") || q.contains("japanese"))
    val isDiag = q.contains("wrong") || q.contains("broken") || q.contains("issue") || q.contains("light") || q.contains("code") || q.contains("door") || q.contains("diagnos") || q.contains("obd") || q.contains("ob2") || q.contains("engine") || q.contains("service")
    val isShortFollowUp = q.length < 20 && !q.contains("search") && !q.contains("find") && !q.contains("look up")
    
    val intercept = (true) && (isGenericFood || isDiag || (true && isShortFollowUp))
    println("isGenericFood: $isGenericFood")
    println("isDiag: $isDiag")
    println("isShortFollowUp: $isShortFollowUp")
    println("intercept: $intercept")
}
