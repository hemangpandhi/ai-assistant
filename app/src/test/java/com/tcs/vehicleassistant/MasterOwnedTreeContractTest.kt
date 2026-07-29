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
 * Ensures every Kotlin file that exists on [REF] under `app/src` stays byte-identical.
 * UI/UX / TTFR work must live in additive parallels, not in master-owned shared classes.
 */
class MasterOwnedTreeContractTest {
    private val seamExceptions = emptySet<String>()

    @Test
    fun masterOwnedKotlinFilesRemainByteIdentical() {
        val discoveredRoot = findRepoRoot(Paths.get(System.getProperty("user.dir")))
        Assume.assumeNotNull(discoveredRoot)
        val repoRoot = requireNotNull(discoveredRoot)

        val refCheck = runGit(repoRoot, "rev-parse", "--verify", "$REF^{commit}")
        Assume.assumeTrue(
            "git or $REF is unavailable",
            refCheck?.exitCode == 0,
        )

        val paths = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(ALLOWLIST_RESOURCE),
        ) { "Missing $ALLOWLIST_RESOURCE" }
            .bufferedReader()
            .useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }

        for (path in paths) {
            if (path in seamExceptions) continue

            val expected = requireNotNull(runGit(repoRoot, "show", "$REF:$path")) {
                "git became unavailable while checking $path"
            }
            assertEquals("git show failed for $path", 0, expected.exitCode)
            assertArrayEquals(
                "$path differs from $REF",
                expected.stdout,
                Files.readAllBytes(repoRoot.resolve(path)),
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
