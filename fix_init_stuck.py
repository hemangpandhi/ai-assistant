import os

file_path = "app/src/main/java/com/tcs/vehicleassistant/AssistantSession.kt"
with open(file_path, "r") as f:
    text = f.read()

# 1. Disable btnMic during initialization in onShow
old_onshow_init = """        if (LLMManager.engine == null || LLMManager.isPrewarming) {
            statusText.text = if (LLMManager.isPrewarming) "Pre-warming Model... This may take 20s" else "Initializing Model..."
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.GONE"""

new_onshow_init = """        if (LLMManager.engine == null || LLMManager.isPrewarming) {
            statusText.text = if (LLMManager.isPrewarming) "Pre-warming Model... This may take 20s" else "Initializing Model..."
            btnOpenApp.visibility = View.GONE
            inputControls.visibility = View.GONE
            btnMic.isEnabled = false
            btnMic.isClickable = false
            btnSend.isEnabled = false"""
text = text.replace(old_onshow_init, new_onshow_init)

old_onsuccess = """                    override fun onSuccess() {
                        statusText.text = "Hi, how can I help you?"
                        inputControls.visibility = View.VISIBLE
                        btnSend.isEnabled = true"""

new_onsuccess = """                    override fun onSuccess() {
                        statusText.text = "Hi, how can I help you?"
                        inputControls.visibility = View.VISIBLE
                        btnMic.isEnabled = true
                        btnMic.isClickable = true
                        btnSend.isEnabled = true"""
text = text.replace(old_onsuccess, new_onsuccess)

# 2. Add Toast to handleQuery if engine is null
old_handle_query = """        if (LLMManager.engine == null || LLMManager.conversation == null) return
        
        startThinkingAnimation()"""

new_handle_query = """        if (LLMManager.engine == null || LLMManager.conversation == null) {
            android.widget.Toast.makeText(context, "Model is still initializing...", android.widget.Toast.LENGTH_SHORT).show()
            stopThinkingAnimation()
            voiceAnimation.state = VoiceAnimationView.State.IDLE
            statusText.visibility = android.view.View.VISIBLE
            btnMic.isEnabled = true
            btnSend.isEnabled = true
            isQueryProcessed = true
            return
        }
        
        startThinkingAnimation()"""
text = text.replace(old_handle_query, new_handle_query)

with open(file_path, "w") as f:
    f.write(text)
