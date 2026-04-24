package com.sj.obd2app.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DbcParserTest {

    @Test
    fun `parses minimal message and signal`() {
        val text = """
            VERSION "demo"

            BU_: PCM TCM

            BO_ 523 ABS_P_20B: 8 ABS
             SG_ BrakePressure_HS : 33|10@0+ (0.2,0) [0|204.6] "Bar" CCM, IPC, PBM
             SG_ VehRefSpeed_HS : 55|16@0+ (0.01,0) [0|327.67] "KPH" PCM, TCM
        """.trimIndent()

        val db = DbcParser.parseText(text, "demo.dbc")
        assertEquals("demo", db.version)
        assertEquals(listOf("PCM", "TCM"), db.nodes)
        assertEquals(1, db.messages.size)

        val msg = db.messages[0]
        assertEquals(523, msg.id)
        assertEquals(false, msg.extended)
        assertEquals("ABS_P_20B", msg.name)
        assertEquals(8, msg.dlc)
        assertEquals("ABS", msg.transmitter)
        assertEquals(2, msg.signals.size)

        val brake = msg.signals[0]
        assertEquals("BrakePressure_HS", brake.name)
        assertEquals(33, brake.startBit)
        assertEquals(10, brake.length)
        assertEquals(false, brake.littleEndian)
        assertEquals(false, brake.signed)
        assertEquals(0.2, brake.factor, 1e-9)
        assertEquals(0.0, brake.offset, 1e-9)
        assertEquals("Bar", brake.unit)
        assertTrue(brake.receivers.containsAll(listOf("CCM", "IPC", "PBM")))

        val speed = msg.signals[1]
        assertEquals("VehRefSpeed_HS", speed.name)
        assertEquals(0.01, speed.factor, 1e-9)
    }

    @Test
    fun `extended id flag is stripped`() {
        val text = "BO_ ${0x80000123L.toInt()} EXT_MSG: 8 ECU\n SG_ A : 0|8@1+ (1,0) [0|0] \"\" ECU"
        val db = DbcParser.parseText(text, "ext.dbc")
        assertEquals(1, db.messages.size)
        val m = db.messages[0]
        assertEquals(0x123, m.id)
        assertEquals(true, m.extended)
    }

    @Test
    fun `ignores unknown sections and records warnings for bad lines`() {
        val text = """
            NS_:
               NS_DESC_

            BS_:

            BO_ 100 M: 8 ECU
             SG_ X : NONSENSE
        """.trimIndent()
        val db = DbcParser.parseText(text, "x.dbc")
        assertEquals(1, db.messages.size)
        assertEquals(0, db.messages[0].signals.size)
        assertTrue(db.warnings.any { it.contains("SG_") })
    }

    @Test
    fun `sign flag parsed as signed`() {
        val text = """
            BO_ 10 M: 8 ECU
             SG_ S : 0|16@1- (1,0) [-32768|32767] "" ECU
        """.trimIndent()
        val db = DbcParser.parseText(text, "s.dbc")
        assertEquals(true, db.messages[0].signals[0].signed)
    }

    @Test
    fun `parses x150 sample snippet`() {
        // Mirrors first BO_ of the provided Jaguar X150 DBC.
        val text = """
            VERSION "HS_CAN"

            BU_: ABS CCM PCM TCM

            BO_ 523 ABS_P_20B: 8 ABS
             SG_ ABSAliveCounter_HS : 37|4@0+ (1,0) [0|14] "Counter" PCM, TCM
             SG_ ABSChecksum_HS : 15|8@0+ (1,0) [0|255] "Counter" PCM, TCM
             SG_ BrakePressure_HS : 33|10@0+ (0.2,0) [0|204.6] "Bar" CCM, PCM
             SG_ VehRefSpeed_HS : 55|16@0+ (0.01,0) [0|327.67] "KPH" PCM, TCM
        """.trimIndent()
        val db = DbcParser.parseText(text, "x150.dbc")
        val (_, sig) = db.findSignal(523, "VehRefSpeed_HS")!!
        assertNotNull(sig)
        assertEquals(55, sig.startBit)
        assertEquals(16, sig.length)
        assertEquals(false, sig.littleEndian)
        assertEquals(0.01, sig.factor, 1e-9)
    }
}
