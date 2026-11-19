package com.obd2logger.data.protocol

import com.obd2logger.domain.model.ManufacturerPidDefinition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManufacturerPidTest {

    @Test
    fun `SafeFormulaEvaluator computes arithmetic and signed16 formulas accurately`() {
        // GM TFT: (A - 40) * 9 / 5 + 32 -> A=130 (90 C) = 194 F
        assertEquals(194.0, SafeFormulaEvaluator.evaluate("(A - 40) * 9 / 5 + 32", byteArrayOf(130.toByte()))!!, 0.01)

        // Ford 6R80 TFT: (signed16(A, B) / 16) * 9 / 5 + 32 -> 0x05 0xA0 = 1440 -> 1440/16 = 90 C -> 194 F
        assertEquals(
            194.0,
            SafeFormulaEvaluator.evaluate("(signed16(A, B) / 16) * 9 / 5 + 32", byteArrayOf(0x05, 0xA0.toByte()))!!,
            0.01
        )

        // Negative signed16 (-10 C = -160 = 0xFF60) -> 14 F
        assertEquals(
            14.0,
            SafeFormulaEvaluator.evaluate("(signed16(A, B) / 16) * 9 / 5 + 32", byteArrayOf(0xFF.toByte(), 0x60))!!,
            0.01
        )

        // Division by zero safely yields null
        assertNull(SafeFormulaEvaluator.evaluate("A / B", byteArrayOf(10, 0)))
    }

    @Test
    fun `SafeFormulaEvaluator rejects unsafe expressions and scripting attempts`() {
        val unsafeFormulas = listOf(
            "eval('1+1')",
            "Runtime.getRuntime().exec('ls')",
            "System.exit(0)",
            "A + X",
            "A = 5",
            "1;2",
            "Math.pow(A, 2)",
            ""
        )
        for (expr in unsafeFormulas) {
            val compiled = SafeFormulaEvaluator.compile(expr)
            assertTrue("Expected unsafe formula '$expr' to be rejected", compiled.isFailure)
        }
    }

    @Test
    fun `ManufacturerPidParser parses valid JSON and rejects malformed JSON or non-read services`() {
        val validJson = """
            {
              "pids": [
                {
                  "id": "gm_tft",
                  "name": "GM TFT",
                  "makes": ["GM"],
                  "moduleName": "TCM",
                  "requestHeader": "7E2",
                  "command": "221940",
                  "expectedBytes": 1,
                  "formula": "(A - 40) * 9 / 5 + 32",
                  "unit": "°F",
                  "min": -40,
                  "max": 350,
                  "category": "Transmission",
                  "source": "Verified"
                }
              ]
            }
        """.trimIndent()
        val ok = ManufacturerPidParser.parseJson(validJson)
        assertTrue(ok.isSuccess)
        val pid = ok.getOrThrow().single()
        assertEquals("GM TFT(°F)", pid.csvColumnHeader)
        assertEquals(0x22, pid.serviceByte)

        // Malformed JSON
        assertTrue(ManufacturerPidParser.parseJson("{ bad json ").isFailure)

        // Unsafe write / actuator / security services MUST be rejected
        for (badCmd in listOf("2E194000", "2F123403", "3101FF00", "2701", "1101")) {
            val badJson = """
                {
                  "pids": [{
                    "id": "bad_cmd",
                    "name": "Bad",
                    "moduleName": "ECM",
                    "requestHeader": "7E0",
                    "command": "$badCmd",
                    "expectedBytes": 1,
                    "formula": "A",
                    "unit": "%",
                    "category": "Engine"
                  }]
                }
            """.trimIndent()
            val res = ManufacturerPidParser.parseJson(badJson)
            assertTrue("Command $badCmd must be rejected", res.isFailure)
        }
    }

    @Test
    fun `ManufacturerPidProber ALWAYS restores default CAN header even when a PID command fails`() = runTest {
        val sent = mutableListOf<String>()
        val transport = object : HeaderCommandTransport {
            override suspend fun sendCommand(command: String, timeoutMs: Long): Result<String> {
                sent.add(command)
                return when {
                    command.startsWith("ATSH") || command.startsWith("ATCRA") || command.startsWith("ATFCSM") ->
                        Result.success("OK\r>")
                    command == "221940" -> Result.success("62 19 40 82\r>")
                    command == "22DEAD" -> Result.failure(IOException("Simulated bus timeout"))
                    else -> Result.success("NO DATA\r>")
                }
            }
        }

        val pids = listOf(
            ManufacturerPidDefinition(
                id = "gm_tft",
                name = "GM TFT",
                moduleName = "TCM",
                requestHeader = "7E2",
                receiveAddress = "7EA",
                command = "221940",
                expectedBytes = 1,
                formula = "(A - 40) * 9 / 5 + 32",
                unit = "°F",
                category = "Transmission",
                source = "Test",
                decimals = 0
            ),
            ManufacturerPidDefinition(
                id = "bad_pid",
                name = "Timeout PID",
                moduleName = "ABS",
                requestHeader = "7B0",
                command = "22DEAD",
                expectedBytes = 1,
                formula = "A",
                unit = "psi",
                category = "ABS",
                source = "Test"
            )
        )

        val decoded = ManufacturerPidProber.probeWithTransport(
            transport = transport,
            candidates = pids,
            protocol = "AUTO, ISO 15765-4 (CAN 11/500)"
        )

        assertEquals("194", decoded["gm_tft"])
        assertNull(decoded["bad_pid"])
        assertNotNull(sent.lastOrNull())
        // Last commands MUST reset ATCRA and restore default functional header 7DF
        assertTrue("ATCRA" in sent)
        assertEquals("ATSH 7DF", sent.last())
    }
}
