package com.sj.obd2app.obd

/**
 * Evaluates arithmetic formulas used in custom PID definitions.
 *
 * Supports the standard Torque Pro / Car Scanner notation:
 *   Variables: A, B, C, D, E, F, G, H (mapped to response data bytes 0–7)
 *   Operators: +, -, *, /
 *   Grouping:  ( )
 *   Constants: decimal numbers (integer or floating point)
 *
 * Example: "((A*256)+B)/100" with A=0x01, B=0xF4 → (1*256 + 244) / 100 = 5.0
 *
 * Uses a simple recursive-descent parser. Thread-safe and stateless.
 */
object PidFormulaParser {

    /** Variable names mapped to byte indices */
    private val VARIABLES = mapOf(
        'A' to 0, 'B' to 1, 'C' to 2, 'D' to 3,
        'E' to 4, 'F' to 5, 'G' to 6, 'H' to 7
    )

    /**
     * Evaluate a formula string with the given data bytes.
     *
     * @param formula  The arithmetic expression, e.g. "((A*256)+B)/100"
     * @param bytes    Response data bytes as IntArray (index 0 = A, 1 = B, etc.)
     * @param signed   If true, treat the raw combined value as signed (two's complement
     *                 is handled by the formula itself; this flag is reserved for future use)
     * @return The computed Double value
     * @throws FormulaException if the formula is malformed
     */
    fun evaluate(formula: String, bytes: IntArray, signed: Boolean = false): Double {
        val tokens = tokenize(formula)
        val parser = Parser(tokens, bytes)
        val result = parser.parseExpression()
        if (parser.hasMore()) {
            throw FormulaException("Unexpected token at position ${parser.position}: '${parser.peek()}'")
        }
        return result
    }

    /**
     * Format the result of [evaluate] as a display string.
     * Chooses decimal places based on magnitude.
     */
    fun format(value: Double): String {
        return when {
            value == value.toLong().toDouble() && kotlin.math.abs(value) < 1_000_000 ->
                String.format("%.0f", value)
            kotlin.math.abs(value) >= 100 -> String.format("%.1f", value)
            kotlin.math.abs(value) >= 1   -> String.format("%.2f", value)
            else                          -> String.format("%.3f", value)
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data class Variable(val index: Int) : Token()
        data class Op(val op: Char) : Token()
        object LParen : Token()
        object RParen : Token()
    }

    private fun tokenize(formula: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val s = formula.replace(" ", "")

        while (i < s.length) {
            val c = s[i]
            when {
                c in VARIABLES -> {
                    tokens.add(Token.Variable(VARIABLES[c]!!))
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    val numStr = s.substring(start, i)
                    tokens.add(Token.Number(numStr.toDoubleOrNull()
                        ?: throw FormulaException("Invalid number: '$numStr'")))
                }
                c == '+' || c == '-' || c == '*' || c == '/' -> {
                    tokens.add(Token.Op(c))
                    i++
                }
                c == '(' -> { tokens.add(Token.LParen); i++ }
                c == ')' -> { tokens.add(Token.RParen); i++ }
                else -> throw FormulaException("Unexpected character: '$c' at position $i")
            }
        }
        return tokens
    }

    // ── Recursive-Descent Parser ─────────────────────────────────────────────

    private class Parser(private val tokens: List<Token>, private val bytes: IntArray) {
        var position = 0

        fun hasMore() = position < tokens.size
        fun peek(): Token? = if (hasMore()) tokens[position] else null

        fun parseExpression(): Double {
            var left = parseTerm()
            while (hasMore()) {
                val tok = peek()
                if (tok is Token.Op && (tok.op == '+' || tok.op == '-')) {
                    position++
                    val right = parseTerm()
                    left = if (tok.op == '+') left + right else left - right
                } else break
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parseUnary()
            while (hasMore()) {
                val tok = peek()
                if (tok is Token.Op && (tok.op == '*' || tok.op == '/')) {
                    position++
                    val right = parseUnary()
                    left = if (tok.op == '*') left * right else {
                        if (right == 0.0) Double.NaN else left / right
                    }
                } else break
            }
            return left
        }

        private fun parseUnary(): Double {
            val tok = peek()
            if (tok is Token.Op && tok.op == '-') {
                position++
                return -parsePrimary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            val tok = peek() ?: throw FormulaException("Unexpected end of formula")
            return when (tok) {
                is Token.Number -> { position++; tok.value }
                is Token.Variable -> {
                    position++
                    if (tok.index < bytes.size) bytes[tok.index].toDouble()
                    else throw FormulaException("Variable index ${tok.index} out of range (only ${bytes.size} bytes available)")
                }
                is Token.LParen -> {
                    position++ // consume '('
                    val value = parseExpression()
                    val closing = peek()
                    if (closing !is Token.RParen) {
                        throw FormulaException("Expected ')' but got ${closing ?: "end of formula"}")
                    }
                    position++ // consume ')'
                    value
                }
                else -> throw FormulaException("Unexpected token: $tok")
            }
        }
    }

    class FormulaException(message: String) : RuntimeException(message)
}
