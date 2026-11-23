/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitsumed.shizucallrecorder.security.AppLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * A fully working basic calculator used as the whole-app lock gate.
 *
 * Typing the saved numeric passcode and pressing "=" unlocks the app (via [AppLockManager.tryUnlock]); any other input
 * is evaluated as normal arithmetic.
 *
 * @param onUnlocked Called once the passcode was verified.
 * @param verifyPasscode Override for previews/tests. Runs off the main thread.
 */
@Composable
fun CalculatorScreen(
    onUnlocked: () -> Unit,
    verifyPasscode: ((String) -> Boolean)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expression by rememberSaveable { mutableStateOf("") }
    var display by rememberSaveable { mutableStateOf("0") }
    /** True right after "=" so the next digit starts a new expression. */
    var justEvaluated by rememberSaveable { mutableStateOf(false) }
    var verifying by rememberSaveable { mutableStateOf(false) }

    fun press(key: String) {
        if (verifying) return
        when (key) {
            "AC" -> { expression = ""; display = "0"; justEvaluated = false }
            "C" -> {
                if (justEvaluated) { expression = ""; display = "0"; justEvaluated = false }
                else { expression = expression.dropLast(1); display = expression.ifEmpty { "0" } }
            }
            "=" -> {
                val input = expression
                if (input.isEmpty()) return
                val digitsOnly = input.all { it.isDigit() }
                if (digitsOnly && (verifyPasscode != null || AppLockManager.isValidPasscode(input))) {
                    verifying = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            verifyPasscode?.invoke(input) ?: AppLockManager.tryUnlock(context, input)
                        }
                        verifying = false
                        if (ok) {
                            // Clear the passcode from the display before leaving.
                            expression = ""; display = "0"; justEvaluated = false
                            onUnlocked()
                        } else {
                            display = CalculatorEngine.evaluate(input); expression = display.takeIf { it != CalculatorEngine.ERROR } ?: ""
                            justEvaluated = true
                        }
                    }
                    return
                }
                val result = CalculatorEngine.evaluate(input)
                display = result
                expression = if (result == CalculatorEngine.ERROR) "" else result
                justEvaluated = true
            }
            "+", "−", "×", "÷" -> {
                justEvaluated = false
                expression = CalculatorEngine.appendOperator(expression, key[0])
                display = expression.ifEmpty { "0" }
            }
            "." -> {
                if (justEvaluated) { expression = ""; justEvaluated = false }
                expression = CalculatorEngine.appendDot(expression)
                display = expression
            }
            else -> { // digit
                if (justEvaluated) { expression = ""; justEvaluated = false }
                if (expression.length < 40) expression += key
                display = expression
            }
        }
    }

    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = if (display.length > 12) 36.sp else 56.sp),
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                val rows = listOf(
                    listOf("AC", "C", "÷", "×"),
                    listOf("7", "8", "9", "−"),
                    listOf("4", "5", "6", "+"),
                    listOf("1", "2", "3", "."),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1.6f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { key -> CalcKey(key, weight = 1f, onClick = { press(key) }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CalcKey("0", weight = 3.1f, onClick = { press("0") })
                        CalcKey("=", weight = 1f, onClick = { press("=") })
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CalcKey(label: String, weight: Float, onClick: () -> Unit) {
    val isOperator = label in setOf("÷", "×", "−", "+", "=")
    val isFunction = label == "AC" || label == "C"
    val colors = when {
        isOperator -> ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800), contentColor = Color.White)
        isFunction -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Button(
        onClick = onClick,
        modifier = Modifier.weight(weight).fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        colors = colors
    ) {
        Text(label, fontSize = 26.sp)
    }
}

/**
 * Pure arithmetic helpers for [CalculatorScreen]. Supports + − × ÷ with standard precedence and decimals.
 */
internal object CalculatorEngine {
    const val ERROR = "Error"
    private val MC = MathContext(16, RoundingMode.HALF_UP)
    private const val OPS = "+−×÷"

    fun appendOperator(expr: String, op: Char): String {
        if (expr.isEmpty()) return if (op == '−') "−" else ""
        val last = expr.last()
        return when {
            last in OPS -> if (expr.length == 1) expr else expr.dropLast(1) + op
            last == '.' -> expr.dropLast(1) + op
            else -> expr + op
        }
    }

    fun appendDot(expr: String): String {
        val currentNumber = expr.takeLastWhile { it !in OPS }
        if (currentNumber.contains('.')) return expr
        return if (currentNumber.isEmpty()) "${expr}0." else "$expr."
    }

    fun evaluate(expr: String): String {
        return try {
            var e = expr
            while (e.isNotEmpty() && (e.last() in OPS || e.last() == '.')) e = e.dropLast(1)
            if (e.isEmpty()) return "0"
            // Tokenize
            val numbers = mutableListOf<BigDecimal>()
            val ops = mutableListOf<Char>()
            var i = 0
            var negateNext = false
            val sb = StringBuilder()
            while (i < e.length) {
                val c = e[i]
                if (c in OPS && sb.isEmpty() && (numbers.size == ops.size)) {
                    // Unary minus at start or after another operator
                    if (c == '−') negateNext = !negateNext else return ERROR
                } else if (c in OPS) {
                    numbers += BigDecimal(sb.toString()).let { if (negateNext) it.negate() else it }
                    negateNext = false
                    sb.clear()
                    ops += c
                } else {
                    sb.append(c)
                }
                i++
            }
            if (sb.isEmpty()) return ERROR
            numbers += BigDecimal(sb.toString()).let { if (negateNext) it.negate() else it }

            // First pass: × and ÷
            val n2 = mutableListOf(numbers[0])
            val o2 = mutableListOf<Char>()
            for (k in ops.indices) {
                val op = ops[k]
                val rhs = numbers[k + 1]
                when (op) {
                    '×' -> n2[n2.lastIndex] = n2.last().multiply(rhs, MC)
                    '÷' -> {
                        if (rhs.signum() == 0) return ERROR
                        n2[n2.lastIndex] = n2.last().divide(rhs, MC)
                    }
                    else -> { o2 += op; n2 += rhs }
                }
            }
            // Second pass: + and −
            var acc = n2[0]
            for (k in o2.indices) {
                acc = if (o2[k] == '+') acc.add(n2[k + 1], MC) else acc.subtract(n2[k + 1], MC)
            }
            format(acc)
        } catch (_: Exception) {
            ERROR
        }
    }

    private fun format(value: BigDecimal): String {
        val rounded = value.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = rounded.toPlainString()
        val result = if (plain.length <= 16) plain else rounded.toString().replace("E+", "e").replace("E", "e")
        return if (result.startsWith("-")) "−" + result.drop(1) else result
    }
}

@Preview(showBackground = true)
@Composable
private fun CalculatorScreenPreview() {
    CalculatorScreen(onUnlocked = {}, verifyPasscode = { false })
}
