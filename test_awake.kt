import android.service.voice.VoiceInteractionSession
import android.content.Context

class TestSession(context: Context) : VoiceInteractionSession(context) {
    fun test() {
        setKeepAwake(true)
    }
}
