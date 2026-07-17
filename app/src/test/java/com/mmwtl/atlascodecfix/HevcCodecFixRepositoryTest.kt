package com.mmwtl.atlascodecfix

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class HevcCodecFixRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultRestoreNeverRunsPreflightOrStagesAssets() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("default"))
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MSMNILE)
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.applyVariant(HevcCodecFixVariant.MSMNILE)

        assertTrue(result.success)
        assertEquals(0, assets.stageCount.get())
        assertEquals(2, adb.commands.size)
        assertTrue(adb.commands.first().contains("set -- restore"))
        assertFalse(adb.commands.any { it.contains(FakeAssets.PREFLIGHT_MARKER) })
    }

    @Test
    fun unsupportedProfileIsRejectedBeforeStaging() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("unsupported"))
        val adb = FakeAdbExecutor(
            detectedVariant = HevcCodecFixVariant.MSMNILE,
            compatibilityStatus = "unsupported"
        )
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.applyVariant(HevcCodecFixVariant.MIN)

        assertFalse(result.success)
        assertEquals(HevcCodecFixCompatibilityStatus.UNSUPPORTED, result.compatibility?.status)
        assertEquals(0, assets.stageCount.get())
        assertEquals(1, adb.commands.size)
    }

    @Test
    fun applySequencesAreSerialized() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("serialized"), stageDelayMs = 80)
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MIN)
        val repository = HevcCodecFixRepository(assets, adb)

        val results = listOf(
            async { repository.applyVariant(HevcCodecFixVariant.MIN) },
            async { repository.applyVariant(HevcCodecFixVariant.MIN) }
        ).awaitAll()

        assertTrue(results.all(HevcCodecFixApplyResult::success))
        assertEquals(1, assets.maxConcurrentStages.get())
    }

    @Test
    fun operationsAcrossRepositoryInstancesAreSerializedProcessWide() = runBlocking {
        val adb = ConcurrentAdbExecutor()
        val repositories = listOf(
            HevcCodecFixRepository(FakeAssets(temporaryFolder.newFolder("process-wide-a")), adb),
            HevcCodecFixRepository(FakeAssets(temporaryFolder.newFolder("process-wide-b")), adb)
        )

        repositories.map { repository ->
            async { repository.detectCurrentVariant() }
        }.awaitAll()

        assertEquals(1, adb.maxConcurrentCommands.get())
    }

    @Test
    fun parserReadsMachineOutputWithoutTextHeuristics() {
        val repository = HevcCodecFixRepository(
            FakeAssets(temporaryFolder.root),
            FakeAdbExecutor(HevcCodecFixVariant.MIN)
        )
        val output = "reason:known_good\nvariant:max\nstatus:supported\n"

        assertEquals("known_good", repository.parseKey(output, "reason"))
        assertEquals(HevcCodecFixVariant.MAX, repository.detectVariant(output))
    }

    @Test
    fun statusDetectionUsesOneDirectRootShellWithoutDeviceLock() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("direct-status"))
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MIN)
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.detectCurrentVariant()

        assertTrue(result.commandSuccess)
        val command = adb.commands.single()
        assertTrue(command.startsWith("su root sh -c "))
        assertEquals(1, Regex("""\bsh -c\b""").findAll(command).count())
        assertFalse(command.contains("/dev/hevc.operation.lock"))
    }

    @Test
    fun applyUsesAdbRootShellAndDevStaging() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("root-shell"))
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MIN)
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.applyVariant(
            variant = HevcCodecFixVariant.MIN,
            skipCompatibilityCheck = true
        )

        assertTrue(result.success)
        val applyCommand = adb.commands.first { it.contains("NEW_DIR=") }
        assertTrue(applyCommand.startsWith("su root sh -c "))
        assertTrue(applyCommand.contains("/dev/hevc"))
    }

    @Test
    fun successfulPreflightIsNotRepeatedInsideRootTransaction() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("single-preflight"))
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MIN)
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.applyVariant(HevcCodecFixVariant.MIN)

        assertTrue(result.success)
        assertEquals(1, adb.commands.count { it.contains(FakeAssets.PREFLIGHT_MARKER) })
        val applyCommand = adb.commands.first { it.contains("NEW_DIR=") }
        assertTrue(applyCommand.contains("PREFLIGHT_VERIFIED=1"))
        assertFalse(applyCommand.contains("SKIP_PREFLIGHT=1"))
    }

    @Test
    fun applyCommandUsesDirectRootShellAndVerifiesBeforeReturning() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("direct-root"))
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MIN)
        val repository = HevcCodecFixRepository(assets, adb)

        assertTrue(
            repository.applyVariant(
                variant = HevcCodecFixVariant.MIN,
                skipCompatibilityCheck = true
            ).success
        )

        val applyCommand = adb.commands.first { it.contains("NEW_DIR=") }
        assertTrue(applyCommand.startsWith("su root sh -c "))
        assertFalse(applyCommand.contains("/dev/hevc.operation.lock"))
        assertTrue(applyCommand.contains("phase:verify_variant"))
        assertTrue(applyCommand.contains("phase:automatic_restore"))
    }

    @Test
    fun postApplyMismatchAutomaticallyRestoresDefault() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("mismatch-recovery"))
        val adb = MismatchThenRestoreAdbExecutor()
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.applyVariant(
            variant = HevcCodecFixVariant.MIN,
            skipCompatibilityCheck = true
        )

        assertFalse(result.success)
        assertTrue(result.restoredToDefault)
        assertEquals(HevcCodecFixVariant.MSMNILE, result.detectedVariant)
        assertTrue(adb.commands.any { it.contains("set -- restore") })
    }

    @Test
    fun diagnosticsAreReadOnlyAndRunThroughDirectRootShell() = runBlocking {
        val assets = FakeAssets(temporaryFolder.newFolder("diagnostics"))
        val adb = FakeAdbExecutor(detectedVariant = HevcCodecFixVariant.MSMNILE)
        val repository = HevcCodecFixRepository(assets, adb)

        val result = repository.collectDiagnostics()

        assertTrue(result.commandSuccess)
        assertEquals(3, adb.commands.size)
        assertTrue(adb.commands.all { it.startsWith("su root sh -c ") })
        assertTrue(adb.commands.all { command ->
            Regex("""\bsh -c\b""").findAll(command).count() == 1
        })
        assertTrue(adb.commands.none { it.contains("/dev/hevc.operation.lock") })
        assertTrue(adb.commands.none { it.contains("mount ") || it.contains("umount") })
        assertTrue(result.output.contains("atlas_diagnostics:2"))
        assertTrue(result.output.contains("section:preflight"))
        assertTrue(result.output.contains("section:detect"))
    }

    private class FakeAssets(
        private val directory: File,
        private val stageDelayMs: Long = 0
    ) : HevcAssetSource {
        val stageCount = AtomicInteger()
        val maxConcurrentStages = AtomicInteger()
        private val activeStages = AtomicInteger()

        override fun readText(assetPath: String): String {
            return if (assetPath == "preflight.sh") PREFLIGHT_MARKER else "echo codecfix"
        }

        override fun getString(resource: Int, vararg arguments: Any): String {
            return "test message"
        }

        override fun stage(variant: HevcCodecFixVariant): File {
            stageCount.incrementAndGet()
            val active = activeStages.incrementAndGet()
            maxConcurrentStages.accumulateAndGet(active, ::maxOf)
            try {
                if (stageDelayMs > 0) Thread.sleep(stageDelayMs)
                return directory
            } finally {
                activeStages.decrementAndGet()
            }
        }

        companion object {
            const val PREFLIGHT_MARKER = "__TEST_PREFLIGHT__"
        }
    }

    private class FakeAdbExecutor(
        private val detectedVariant: HevcCodecFixVariant,
        private val compatibilityStatus: String = "supported"
    ) : AdbCommandExecutor {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun execute(command: String, timeoutMs: Long): AdbCommandResult {
            commands += command
            val output = when {
                command.contains(FakeAssets.PREFLIGHT_MARKER) -> """
                    hevc_preflight:1
                    variant:msmnile
                    score:10
                    status:$compatibilityStatus
                    auto_apply:${if (compatibilityStatus == "supported") "yes" else "no"}
                    reason:test
                """.trimIndent()
                command.contains("set -- restore") -> "status:ok\nvariant:msmnile"
                command.contains("NEW_DIR=") -> "status:ok\nvariant:${detectedVariant.argument}"
                else -> "variant:${detectedVariant.argument}"
            }
            return AdbCommandResult(stdout = output, exitCode = 0)
        }
    }

    private class MismatchThenRestoreAdbExecutor : AdbCommandExecutor {
        val commands = mutableListOf<String>()
        private var currentVariant = HevcCodecFixVariant.MSMNILE

        override suspend fun execute(command: String, timeoutMs: Long): AdbCommandResult {
            commands += command
            val output = when {
                command.contains("NEW_DIR=") -> {
                    currentVariant = HevcCodecFixVariant.MAX
                    "status:ok\nvariant:max"
                }
                command.contains("set -- restore") -> {
                    currentVariant = HevcCodecFixVariant.MSMNILE
                    "status:ok\nvariant:msmnile"
                }
                else -> "variant:${currentVariant.argument}"
            }
            return AdbCommandResult(stdout = output, exitCode = 0)
        }
    }

    private class ConcurrentAdbExecutor : AdbCommandExecutor {
        val maxConcurrentCommands = AtomicInteger()
        private val activeCommands = AtomicInteger()

        override suspend fun execute(command: String, timeoutMs: Long): AdbCommandResult {
            val active = activeCommands.incrementAndGet()
            maxConcurrentCommands.accumulateAndGet(active, ::maxOf)
            return try {
                delay(80)
                AdbCommandResult(stdout = "variant:msmnile", exitCode = 0)
            } finally {
                activeCommands.decrementAndGet()
            }
        }
    }
}
