import re

with open("app/src/main/java/com/example/gemininano/ToolManager.kt", "r") as f:
    code = f.read()

getter = """
    fun getToolDefinition(toolCall: String): ToolDefinition? {
        val commandName = toolCall.substringBefore("(").trim()
        return activeTools[commandName]
    }
    
    fun getAllTools(): Map<String, ToolDefinition> = activeTools
"""
code = code.replace("    fun getToolDefinition(toolCall: String): ToolDefinition? {\n        val commandName = toolCall.substringBefore(\"(\").trim()\n        return activeTools[commandName]\n    }", getter)

old_relevant = """    fun getRelevantTools(query: String): List<ToolDefinition> {
        if (query.isBlank()) return activeTools.values.toList()
        
        val q = query.lowercase()
        val scoredTools = activeTools.values.map { tool ->
            var score = 0
            tool.keywords?.forEach { keyword ->
                if (q.contains(keyword)) {
                    score += 1
                }
            }
            // Always include generic/global tools if they have no keywords
            if (tool.keywords == null) {
                score += 1
            }
            Pair(tool, score)
        }
        
        val relevant = scoredTools.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return if (relevant.size > 10) relevant.take(10) else if (relevant.isNotEmpty()) relevant else activeTools.values.take(10).toList()
    }"""

new_relevant = """    fun getRelevantTools(query: String): List<ToolDefinition> {
        if (query.isBlank()) return activeTools.values.toList()
        return SemanticSearchManager.search(query, 10)
    }"""

code = code.replace(old_relevant, new_relevant)

init_end = """        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tools from custom_properties.json", e)
        }
    }"""

new_init_end = """        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tools from custom_properties.json", e)
        }
        
        // Initialize Semantic Search RAG asynchronously
        SemanticSearchManager.initialize(context)
        SemanticSearchManager.buildToolEmbeddingsCache()
    }"""
    
code = code.replace(init_end, new_init_end)

with open("app/src/main/java/com/example/gemininano/ToolManager.kt", "w") as f:
    f.write(code)

print("ToolManager.kt patched with Semantic Search.")
