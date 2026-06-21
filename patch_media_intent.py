import re

path = "app/src/main/java/com/example/gemininano/handlers/MediaToolHandler.kt"
with open(path, "r") as f:
    content = f.read()

old_logic = """                    // Always launch the Spotify UI so the user can visually see it playing
                    val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
                    searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intentHandler != null) intentHandler(searchIntent) else context.startActivity(searchIntent)
                    success = true"""

new_logic = """                    // Use standard Android MediaSearch intent to support all players (Spotify, YouTube Music, etc)
                    val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    searchIntent.putExtra(android.app.SearchManager.QUERY, query)
                    searchIntent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
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
