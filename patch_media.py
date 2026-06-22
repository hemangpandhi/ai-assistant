path = "app/src/main/java/com/example/gemininano/handlers/MediaToolHandler.kt"
with open(path, "r") as f:
    content = f.read()

old_logic = """                    val controllers = mediaSessionManager.getActiveSessions(null)
                    var spotifyController = controllers.find { it.packageName.contains("spotify", ignoreCase = true) }
                    
                    if (spotifyController != null) {
                        spotifyController.transportControls.playFromSearch(query, null)
                        success = true
                    } else if (controllers.isNotEmpty()) {
                        // Fallback to any active media session
                        controllers[0].transportControls.playFromSearch(query, null)
                        success = true
                    } else {
                        // Fallback: Launch intent silently if possible, or normally if no active sessions exist
                        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                        searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                        success = true
                    }"""

new_logic = """                    // Always launch the Spotify UI so the user can visually see it playing
                    val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                    searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                    success = true"""

if old_logic in content:
    content = content.replace(old_logic, new_logic)
    with open(path, "w") as f:
        f.write(content)
    print("Patched MediaToolHandler successfully.")
else:
    print("Could not find old_logic string.")
