val rawToolCall = "<TOOL>startNavigationTo(\"Tokyo\")</TOOL>"
val toolCall = rawToolCall.trim()
val key = "startNavigationTo"
val startsWith = toolCall.lowercase().startsWith(key.lowercase())
println("StartsWith: $startsWith")

val commandName = "startNavigationTo(\"Tokyo\")".substringAfter("<TOOL>").substringBefore("</TOOL>").substringBefore("(")
println("CommandName: $commandName")
