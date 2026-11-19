package com.obd2logger.data.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ClearCodesGuardTest {

    @Test
    fun `clear codes is BLOCKED if pre-clear snapshot fails to save and Mode 04 is never sent`() = runTest {
        val sentCommands = mutableListOf<String>()
        val transport = object : ObdClearTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                sentCommands.add(command)
                return when (command) {
                    "0101" -> Result.success("41 01 82 07 65 04\r>")
                    "03" -> Result.success("43 01 01 71\r>")
                    "07" -> Result.success("47 00\r>")
                    "0A" -> Result.success("4A 01 01 71\r>")
                    "020200" -> Result.success("42 02 00 01 71\r>")
                    "04" -> Result.success("44\r>")
                    else -> Result.success("NO DATA\r>")
                }
            }
        }
        val failingWriter = object : PreClearSnapshotWriter {
            override fun saveSnapshot(filename: String, jsonContent: String): Result<String> {
                return Result.failure(IOException("Disk full"))
            }
        }

        val result = ClearCodesManager.executeClearSequence(
            transport = transport,
            snapshotWriter = failingWriter,
            knownVin = "1HGCM82633A004352",
            isCanProtocol = true
        )

        assertTrue(result is ClearCodesResult.SnapshotFailed)
        assertFalse("Mode 04 must NEVER be sent if snapshot save fails", "04" in sentCommands)
    }

    @Test
    fun `clear codes saves precleared json first, sends Mode 04, and verifies cleared DTCs`() = runTest {
        val sentCommands = mutableListOf<String>()
        var cleared = false
        val transport = object : ObdClearTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                sentCommands.add(command)
                return when (command) {
                    "0101" -> Result.success(if (!cleared) "41 01 81 07 65 04\r>" else "41 01 00 07 65 65\r>")
                    "03" -> Result.success(if (!cleared) "43 01 01 71\r>" else "43 00\r>")
                    "07" -> Result.success("47 00\r>")
                    "0A" -> Result.success("4A 01 01 71\r>")
                    "020200" -> Result.success("42 02 00 01 71\r>")
                    "04" -> {
                        cleared = true
                        Result.success("44\r>")
                    }
                    else -> Result.success("NO DATA\r>")
                }
            }
        }

        var savedName: String? = null
        var savedJson: String? = null
        val okWriter = object : PreClearSnapshotWriter {
            override fun saveSnapshot(filename: String, jsonContent: String): Result<String> {
                savedName = filename
                savedJson = jsonContent
                return Result.success("content://media/external/file/99")
            }
        }

        val result = ClearCodesManager.executeClearSequence(
            transport = transport,
            snapshotWriter = okWriter,
            knownVin = "1HGCM82633A004352",
            isCanProtocol = true,
            nowMs = 1_795_012_325_000L
        )

        assertTrue(result is ClearCodesResult.Success)
        val success = result as ClearCodesResult.Success
        assertTrue(savedName!!.endsWith("_precleared.json"))
        assertTrue(savedJson!!.contains("P0171"))
        assertTrue(savedJson!!.contains("System Too Lean (Bank 1)"))
        assertTrue(savedJson!!.contains("1HGCM82633A004352"))
        assertTrue("04" in sentCommands)
        assertEquals(false, success.postMilOn)
        assertEquals(0, success.remainingStored.size)
        assertEquals(1, success.remainingPermanent.size)
    }

    @Test
    fun `clear codes detects ECU negative response 7F 04 22 when engine is running`() = runTest {
        val transport = object : ObdClearTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                return when (command) {
                    "0101" -> Result.success("41 01 81 07 65 04\r>")
                    "03" -> Result.success("43 01 01 71\r>")
                    "04" -> Result.success("7F 04 22\r>")
                    else -> Result.success("NO DATA\r>")
                }
            }
        }
        val okWriter = object : PreClearSnapshotWriter {
            override fun saveSnapshot(filename: String, jsonContent: String) =
                Result.success("content://media/external/file/100")
        }

        val result = ClearCodesManager.executeClearSequence(
            transport = transport,
            snapshotWriter = okWriter,
            knownVin = "1HGCM82633A004352",
            isCanProtocol = true
        )

        assertTrue(result is ClearCodesResult.Refused)
        val refused = result as ClearCodesResult.Refused
        assertTrue(refused.reason.contains("7F 04 22") || refused.reason.contains("engine OFF"))
    }
}
