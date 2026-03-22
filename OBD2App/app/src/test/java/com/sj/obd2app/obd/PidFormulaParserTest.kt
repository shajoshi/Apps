package com.sj.obd2app.obd

import org.junit.Assert.*
import org.junit.Test

class PidFormulaParserTest {

    private val DELTA = 0.001

    // ── Basic Expressions ────────────────────────────────────────────────────

    @Test
    fun `single variable A`() {
        val result = PidFormulaParser.evaluate("A", intArrayOf(42))
        assertEquals(42.0, result, DELTA)
    }

    @Test
    fun `constant value`() {
        val result = PidFormulaParser.evaluate("100", intArrayOf())
        assertEquals(100.0, result, DELTA)
    }

    @Test
    fun `simple addition`() {
        val result = PidFormulaParser.evaluate("A+B", intArrayOf(10, 20))
        assertEquals(30.0, result, DELTA)
    }

    @Test
    fun `simple subtraction`() {
        val result = PidFormulaParser.evaluate("A-B", intArrayOf(50, 20))
        assertEquals(30.0, result, DELTA)
    }

    @Test
    fun `simple multiplication`() {
        val result = PidFormulaParser.evaluate("A*256", intArrayOf(3))
        assertEquals(768.0, result, DELTA)
    }

    @Test
    fun `simple division`() {
        val result = PidFormulaParser.evaluate("A/10", intArrayOf(50))
        assertEquals(5.0, result, DELTA)
    }

    // ── Operator Precedence ──────────────────────────────────────────────────

    @Test
    fun `multiplication before addition`() {
        val result = PidFormulaParser.evaluate("A*256+B", intArrayOf(1, 244))
        assertEquals(500.0, result, DELTA)
    }

    @Test
    fun `parentheses override precedence`() {
        val result = PidFormulaParser.evaluate("(A+B)*2", intArrayOf(10, 20))
        assertEquals(60.0, result, DELTA)
    }

    // ── Jaguar XF Extended PID Formulas ──────────────────────────────────────

    @Test
    fun `Jaguar yaw rate formula - ((A*256)+B) div 100`() {
        // Simulated response: A=0x01, B=0xF4 → (256+244)/100 = 5.0
        val result = PidFormulaParser.evaluate("((A*256)+B)/100", intArrayOf(0x01, 0xF4))
        assertEquals(5.0, result, DELTA)
    }

    @Test
    fun `Jaguar yaw rate formula - zero`() {
        val result = PidFormulaParser.evaluate("((A*256)+B)/100", intArrayOf(0x00, 0x00))
        assertEquals(0.0, result, DELTA)
    }

    @Test
    fun `Jaguar lateral G formula`() {
        // A=0x00, B=0x64 → (0+100)/100 = 1.0
        val result = PidFormulaParser.evaluate("((A*256)+B)/100", intArrayOf(0x00, 0x64))
        assertEquals(1.0, result, DELTA)
    }

    @Test
    fun `steering angle with offset - ((A*256)+B)-32768`() {
        // A=0x80, B=0x00 → (32768+0)-32768 = 0 (center)
        val result = PidFormulaParser.evaluate("((A*256)+B)-32768", intArrayOf(0x80, 0x00))
        assertEquals(0.0, result, DELTA)

        // A=0x80, B=0x64 → (32768+100)-32768 = 100 (right turn)
        val result2 = PidFormulaParser.evaluate("((A*256)+B)-32768", intArrayOf(0x80, 0x64))
        assertEquals(100.0, result2, DELTA)
    }

    // ── Unary Minus ──────────────────────────────────────────────────────────

    @Test
    fun `negative constant`() {
        val result = PidFormulaParser.evaluate("-40", intArrayOf())
        assertEquals(-40.0, result, DELTA)
    }

    @Test
    fun `offset with negative - A-40`() {
        // Standard OBD coolant temp formula
        val result = PidFormulaParser.evaluate("A-40", intArrayOf(80))
        assertEquals(40.0, result, DELTA)
    }

    // ── Nested Parentheses ───────────────────────────────────────────────────

    @Test
    fun `deeply nested parentheses`() {
        val result = PidFormulaParser.evaluate("((A+B)*(C+D))/10", intArrayOf(2, 3, 4, 6))
        // (2+3)*(4+6)/10 = 5*10/10 = 5.0
        assertEquals(5.0, result, DELTA)
    }

    // ── Edge Cases ───────────────────────────────────────────────────────────

    @Test
    fun `division by zero returns NaN`() {
        val result = PidFormulaParser.evaluate("A/0", intArrayOf(10))
        assertTrue(result.isNaN())
    }

    @Test
    fun `spaces in formula are ignored`() {
        val result = PidFormulaParser.evaluate("A * 256 + B", intArrayOf(1, 100))
        assertEquals(356.0, result, DELTA)
    }

    @Test(expected = PidFormulaParser.FormulaException::class)
    fun `variable out of range throws`() {
        PidFormulaParser.evaluate("C", intArrayOf(10)) // only 1 byte, C needs index 2
    }

    @Test(expected = PidFormulaParser.FormulaException::class)
    fun `malformed formula throws`() {
        PidFormulaParser.evaluate("A+", intArrayOf(10))
    }

    @Test(expected = PidFormulaParser.FormulaException::class)
    fun `unmatched parenthesis throws`() {
        PidFormulaParser.evaluate("(A+B", intArrayOf(1, 2))
    }

    // ── Format ───────────────────────────────────────────────────────────────

    @Test
    fun `format integer values without decimals`() {
        assertEquals("42", PidFormulaParser.format(42.0))
        assertEquals("0", PidFormulaParser.format(0.0))
    }

    @Test
    fun `format small values with more precision`() {
        assertEquals("0.500", PidFormulaParser.format(0.5))
    }

    @Test
    fun `format medium values with 2 decimals`() {
        assertEquals("5.00", PidFormulaParser.format(5.001))
    }

    @Test
    fun `format large values with 1 decimal`() {
        assertEquals("256.5", PidFormulaParser.format(256.512))
    }

    // ── CustomPid Integration ────────────────────────────────────────────────

    @Test
    fun `CustomPid responseHeader for Mode 22`() {
        val pid = CustomPid(name = "Yaw Rate", header = "760", mode = "22", pid = "0456")
        assertEquals("620456", pid.responseHeader)
    }

    @Test
    fun `CustomPid commandString`() {
        val pid = CustomPid(name = "Yaw Rate", header = "760", mode = "22", pid = "0456")
        assertEquals("220456", pid.commandString)
    }

    @Test
    fun `CustomPid cacheKey includes header`() {
        val pid = CustomPid(name = "Yaw Rate", header = "760", mode = "22", pid = "0456")
        assertEquals("EXT_760_220456", pid.cacheKey)
    }

    @Test
    fun `CustomPid cacheKey uses default header when empty`() {
        val pid = CustomPid(name = "Test", mode = "22", pid = "ABCD")
        assertEquals("EXT_7DF_22ABCD", pid.cacheKey)
    }
}
