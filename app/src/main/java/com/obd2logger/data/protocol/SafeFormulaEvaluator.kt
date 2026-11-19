package com.obd2logger.data.protocol

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Safe recursive-descent arithmetic expression parser and evaluator for manufacturer PID formulas.
 * Never uses reflection, scripting engines, or eval().
 *
 * Supported variables:
 * - `A`..`H`: unsigned payload bytes (0..255)
 * - `S_A`..`S_D`: signed 8-bit payload bytes (-128..127)
 *
 * Supported functions:
 * - `u16(hi, lo)`: unsigned 16-bit integer
 * - `signed8(x)`: signed 8-bit integer
 * - `signed16(hi, lo)`: signed 16-bit integer
 * - `bit(x, n)`: extracts bit `n` (0 or 1)
 * - `min(a, b)`, `max(a, b)`, `abs(x)`
 */
object SafeFormulaEvaluator {

    sealed interface ExprNode {
        fun eval(bytes: ByteArray): Double
    }

    private data class ConstNode(val value: Double) : ExprNode {
        override fun eval(bytes: ByteArray): Double = value
    }

    private data class VarNode(val index: Int, val signed: Boolean) : ExprNode {
        override fun eval(bytes: ByteArray): Double {
            if (index < 0 || index >= bytes.size) {
                throw IllegalArgumentException("Byte index $index out of range (${bytes.size})")
            }
            val raw = bytes[index].toInt() and 0xFF
            return if (signed) raw.toByte().toDouble() else raw.toDouble()
        }
    }

    private data class UnaryNode(val op: Char, val inner: ExprNode) : ExprNode {
        override fun eval(bytes: ByteArray): Double {
            val v = inner.eval(bytes)
            return if (op == '-') -v else v
        }
    }

    private data class BinaryNode(val op: Char, val left: ExprNode, val right: ExprNode) : ExprNode {
        override fun eval(bytes: ByteArray): Double {
            val l = left.eval(bytes)
            val r = right.eval(bytes)
            return when (op) {
                '+' -> l + r
                '-' -> l - r
                '*' -> l * r
                '/' -> {
                    if (r == 0.0) throw ArithmeticException("Division by zero")
                    l / r
                }
                '%' -> {
                    if (r == 0.0) throw ArithmeticException("Modulo by zero")
                    l % r
                }
                else -> throw IllegalStateException("Unsupported operator '$op'")
            }
        }
    }

    private data class FuncNode(val name: String, val args: List<ExprNode>) : ExprNode {
        override fun eval(bytes: ByteArray): Double {
            val values = args.map { it.eval(bytes) }
            return when (name) {
                "u16" -> {
                    val hi = values[0].toInt() and 0xFF
                    val lo = values[1].toInt() and 0xFF
                    ((hi shl 8) or lo).toDouble()
                }
                "signed8" -> (values[0].toInt() and 0xFF).toByte().toDouble()
                "signed16" -> {
                    val hi = values[0].toInt() and 0xFF
                    val lo = values[1].toInt() and 0xFF
                    (((hi shl 8) or lo).toShort()).toDouble()
                }
                "bit" -> {
                    val v = values[0].toInt()
                    val bitIdx = values[1].toInt().coerceIn(0, 31)
                    ((v ushr bitIdx) and 1).toDouble()
                }
                "min" -> min(values[0], values[1])
                "max" -> max(values[0], values[1])
                "abs" -> abs(values[0])
                else -> throw IllegalStateException("Unknown function '$name'")
            }
        }
    }

    class CompiledFormula internal constructor(private val root: ExprNode) {
        fun evaluate(bytes: ByteArray): Double? {
            return try {
                val result = root.eval(bytes)
                if (result.isFinite()) result else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun compile(formula: String): Result<CompiledFormula> {
        val trimmed = formula.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Formula cannot be empty"))
        }
        return runCatching {
            val parser = Parser(trimmed)
            val node = parser.parseExpression()
            parser.expectEnd()
            CompiledFormula(node)
        }
    }

    fun evaluate(formula: String, bytes: ByteArray): Double? {
        val compiled = compile(formula).getOrNull() ?: return null
        return compiled.evaluate(bytes)
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun expectEnd() {
            skipWhitespace()
            if (pos < src.length) {
                throw IllegalArgumentException("Unexpected token '${src[pos]}' at position $pos")
            }
        }

        fun parseExpression(): ExprNode {
            var left = parseTerm()
            while (true) {
                skipWhitespace()
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                    val op = src[pos++]
                    val right = parseTerm()
                    left = BinaryNode(op, left, right)
                } else {
                    break
                }
            }
            return left
        }

        private fun parseTerm(): ExprNode {
            var left = parseUnary()
            while (true) {
                skipWhitespace()
                if (pos < src.length && (src[pos] == '*' || src[pos] == '/' || src[pos] == '%')) {
                    val op = src[pos++]
                    val right = parseUnary()
                    left = BinaryNode(op, left, right)
                } else {
                    break
                }
            }
            return left
        }

        private fun parseUnary(): ExprNode {
            skipWhitespace()
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                val op = src[pos++]
                return UnaryNode(op, parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): ExprNode {
            skipWhitespace()
            if (pos >= src.length) {
                throw IllegalArgumentException("Unexpected end of formula")
            }

            val ch = src[pos]
            if (ch == '(') {
                pos++
                val inner = parseExpression()
                skipWhitespace()
                if (pos >= src.length || src[pos] != ')') {
                    throw IllegalArgumentException("Missing closing ')' at position $pos")
                }
                pos++
                return inner
            }

            if (ch.isDigit() || ch == '.') {
                return parseNumber()
            }

            if (ch.isLetter() || ch == '_') {
                return parseIdentifierOrCall()
            }

            throw IllegalArgumentException("Disallowed character '$ch' at position $pos")
        }

        private fun parseNumber(): ExprNode {
            val start = pos
            var sawDot = false
            while (pos < src.length) {
                val c = src[pos]
                if (c.isDigit()) {
                    pos++
                } else if (c == '.' && !sawDot) {
                    sawDot = true
                    pos++
                } else {
                    break
                }
            }
            val token = src.substring(start, pos)
            val num = token.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number '$token'")
            return ConstNode(num)
        }

        private fun parseIdentifierOrCall(): ExprNode {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) {
                pos++
            }
            val ident = src.substring(start, pos)
            skipWhitespace()

            if (pos < src.length && src[pos] == '(') {
                pos++ // consume '('
                val args = mutableListOf<ExprNode>()
                skipWhitespace()
                if (pos < src.length && src[pos] != ')') {
                    args.add(parseExpression())
                    while (true) {
                        skipWhitespace()
                        if (pos < src.length && src[pos] == ',') {
                            pos++
                            args.add(parseExpression())
                        } else {
                            break
                        }
                    }
                }
                skipWhitespace()
                if (pos >= src.length || src[pos] != ')') {
                    throw IllegalArgumentException("Missing ')' in call to $ident")
                }
                pos++
                val expectedArity = when (ident) {
                    "u16", "signed16", "bit", "min", "max" -> 2
                    "signed8", "abs" -> 1
                    else -> throw IllegalArgumentException("Unsafe or unknown function '$ident'")
                }
                if (args.size != expectedArity) {
                    throw IllegalArgumentException("Function '$ident' expects $expectedArity argument(s)")
                }
                return FuncNode(ident, args)
            }

            // Variable lookup
            if (ident.length == 1 && ident[0] in 'A'..'H') {
                return VarNode(index = ident[0] - 'A', signed = false)
            }
            if (ident.length == 3 && ident.startsWith("S_") && ident[2] in 'A'..'D') {
                return VarNode(index = ident[2] - 'A', signed = true)
            }
            throw IllegalArgumentException("Unknown or disallowed identifier '$ident'")
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
