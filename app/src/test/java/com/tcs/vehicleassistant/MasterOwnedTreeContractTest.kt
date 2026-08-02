package com.tcs.vehicleassistant

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/**
 * Ensures every Kotlin file listed in the allowlist stays byte-identical to
 * [REF] under the same `app/src/main/java` path. UI/UX extensions live in
 * `com/tcs/vehicleassistant/assistant/` and `com/assistant/` packages.
 */
class MasterOwnedTreeContractTest {
    private val seamExceptions = setOf(
        // Design IVI glanceable panels (ClimatePanelActivity / VehiclePanelActivity).
        "app/src/main/java/com/tcs/vehicleassistant/handlers/SystemToolHandler.kt",
        "app/src/main/java/com/tcs/vehicleassistant/handlers/ToolHandlerRegistry.kt",
        // Bare-name wake alias (hey iris → also iris) via WakeWordPhrasePolicy.
        "app/src/main/java/com/tcs/vehicleassistant/WakeWordService.kt",
    )

    @Test
    fun masterOwnedKotlinFilesRemainByteIdentical() {
        val discoveredRoot = findRepoRoot(Paths.get(System.getProperty("user.dir")))
        Assume.assumeNotNull(discoveredRoot)
        val repoRoot = requireNotNull(discoveredRoot)

        val refCheck = runGit(repoRoot, "rev-parse", "--verify", "$REF^{commit}")
        Assume.assumeTrue("git or $REF is unavailable", refCheck?.exitCode == 0)

        val paths = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(ALLOWLIST_RESOURCE),
        ) { "Missing $ALLOWLIST_RESOURCE" }
            .bufferedReader()
            .useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }

        for (localPath in paths) {
            if (localPath in seamExceptions) continue

            val expected = requireNotNull(runGit(repoRoot, "show", "$REF:$localPath")) {
                "git became unavailable while checking $localPath"
            }
            assertEquals("git show failed for $localPath", 0, expected.exitCode)
            assertArrayEquals(
                "$localPath differs from $REF:$localPath",
                expected.stdout,
                Files.readAllBytes(repoRoot.resolve(localPath)),
            )
        }
    }

    private fun findRepoRoot(start: Path): Path? {
        var candidate: Path? = start.toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.exists(candidate.resolve(".git"))) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun runGit(repoRoot: Path, vararg args: String): CommandResult? =
        try {
            val process = ProcessBuilder(
                listOf("git", "-C", repoRoot.toString()) + args,
            )
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val stdout = process.inputStream.use { it.readBytes() }
            CommandResult(process.waitFor(), stdout)
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
    )

    companion object {
        private const val REF = "origin/master"
        private const val ALLOWLIST_RESOURCE = "master_owned_kotlin.txt"
    }
}
