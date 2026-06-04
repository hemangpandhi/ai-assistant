import re

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "r") as f:
    content = f.read()

old_ondone = """                            if (pendingTools.isNotEmpty()) {
                                stopThinkingAnimation()
                                voiceAnimation.state = VoiceAnimationView.State.LISTENING
                                val feedbacks = kotlinx.coroutines.awaitAll(*pendingTools.toTypedArray()).filterNotNull()
                                toolFeedbacks.addAll(feedbacks)
                            }"""

new_ondone = """                            if (pendingTools.isNotEmpty()) {
                                setKeepAwake(true)
                                stopThinkingAnimation()
                                voiceAnimation.state = VoiceAnimationView.State.LISTENING
                                val feedbacks = kotlinx.coroutines.awaitAll(*pendingTools.toTypedArray()).filterNotNull()
                                toolFeedbacks.addAll(feedbacks)
                                setKeepAwake(false)
                            }"""

content = content.replace(old_ondone, new_ondone)

with open("app/src/main/java/com/example/gemininano/AssistantSession.kt", "w") as f:
    f.write(content)

print("Applied patch_keep_awake.py successfully!")
