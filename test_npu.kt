import com.google.ai.edge.litertlm.Backend
fun main() {
    val methods = Backend::class.java.declaredClasses
    for (m in methods) {
        println(m.name)
    }
}
