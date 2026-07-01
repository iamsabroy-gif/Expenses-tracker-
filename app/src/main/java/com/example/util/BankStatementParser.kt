package com.example.util

import com.example.data.local.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object BankStatementParser {

    data class ParsedRecord(
        val date: Long,
        val title: String,
        val amount: Double,
        val type: String, // "INCOME" or "EXPENSE"
        val category: String,
        val rawLine: String
    )

    fun parseStatementText(text: String): List<ParsedRecord> {
        val lines = text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isHeaderLine(it) }

        val parsedRecords = mutableListOf<ParsedRecord>()

        for (line in lines) {
            try {
                val record = parseSingleLine(line)
                if (record != null) {
                    parsedRecords.add(record)
                }
            } catch (e: Exception) {
                // Skip lines that fail to parse
            }
        }

        return parsedRecords
    }

    private fun isHeaderLine(line: String): Boolean {
        val lower = line.lowercase()
        return (lower.contains("date") && lower.contains("description") && lower.contains("amount")) ||
                lower.startsWith("date,") || lower.startsWith("id,")
    }

    private fun parseSingleLine(line: String): ParsedRecord? {
        // Try parsing as CSV/TSV first
        val separators = listOf(",", ";", "\t", "|")
        var fields: List<String> = emptyList()
        var usedSeparator = ""

        for (sep in separators) {
            if (line.contains(sep)) {
                val tempFields = line.split(sep).map { it.trim() }
                if (tempFields.size >= 3) {
                    fields = tempFields
                    usedSeparator = sep
                    break
                }
            }
        }

        if (fields.isNotEmpty()) {
            return parseFields(fields, line)
        }

        // Unstructured text fallback: search for keywords and numbers
        return parseUnstructuredLine(line)
    }

    private fun parseFields(fields: List<String>, rawLine: String): ParsedRecord? {
        // We need an amount, a type, a description/title, and optionally a date
        var parsedAmount: Double? = null
        var isCredit = false
        var isDebit = false
        var titleCandidate = ""
        var dateCandidate: Long = System.currentTimeMillis()

        // 1. Identify Type and Amount
        for (field in fields) {
            val cleanField = field.lowercase()
            
            // Check for credit / debit indicators
            if (cleanField == "cr" || cleanField == "credit" || cleanField == "c" || cleanField == "deposit" || cleanField.contains("credit")) {
                isCredit = true
            } else if (cleanField == "dr" || cleanField == "debit" || cleanField == "d" || cleanField == "withdrawal" || cleanField.contains("debit")) {
                isDebit = true
            }

            // Attempt to parse amount
            if (parsedAmount == null) {
                // Strip currency symbols and signs
                val numberOnly = field.replace(Regex("[^\\d\\.\\-]"), "")
                val doubleVal = numberOnly.toDoubleOrNull()
                if (doubleVal != null && doubleVal != 0.0) {
                    // Check if the amount itself contains minus/plus sign
                    if (doubleVal < 0) {
                        isDebit = true
                        parsedAmount = kotlin.math.abs(doubleVal)
                    } else if (field.contains("+")) {
                        isCredit = true
                        parsedAmount = doubleVal
                    } else {
                        parsedAmount = doubleVal
                    }
                }
            }
        }

        if (parsedAmount == null) return null

        // 2. Identify Date and Title
        val parsedFields = mutableListOf<String>()
        for (field in fields) {
            // If it parses as a date, use it
            val ts = tryParseDate(field)
            if (ts != null) {
                dateCandidate = ts
            } else {
                // Check if it's the amount or type field
                val isAmountField = field.replace(Regex("[^\\d\\.\\-]"), "").toDoubleOrNull() != null
                val isTypeField = isTypeKeyword(field)
                if (!isAmountField && !isTypeField && field.isNotEmpty()) {
                    parsedFields.add(field)
                }
            }
        }

        titleCandidate = parsedFields.joinToString(" ").trim()
        if (titleCandidate.isEmpty()) {
            titleCandidate = "Transaction"
        }

        // Heuristics for final type determination
        val type = if (isCredit && !isDebit) {
            "INCOME"
        } else if (isDebit && !isCredit) {
            "EXPENSE"
        } else {
            // Default check based on title or amount sign, or default to expense
            if (titleCandidate.lowercase().contains("refund") || titleCandidate.lowercase().contains("salary")) {
                "INCOME"
            } else {
                "EXPENSE"
            }
        }

        val category = getCategoryForTitle(titleCandidate)

        return ParsedRecord(
            date = dateCandidate,
            title = titleCandidate,
            amount = parsedAmount,
            type = type,
            category = category,
            rawLine = rawLine
        )
    }

    private fun parseUnstructuredLine(line: String): ParsedRecord? {
        val lower = line.lowercase()
        
        // 1. Detect credit/debit indicators
        var isCredit = lower.contains("cr") || lower.contains("credit") || lower.contains("deposit") || lower.contains("+")
        var isDebit = lower.contains("dr") || lower.contains("debit") || lower.contains("withdrawal") || lower.contains("-")

        // 2. Find numbers that look like prices (decimal values, or integers at the end)
        val wordTokens = line.split(Regex("\\s+"))
        var parsedAmount: Double? = null

        for (token in wordTokens) {
            val cleanToken = token.replace(Regex("[^\\d\\.\\-]"), "")
            val doubleVal = cleanToken.toDoubleOrNull()
            if (doubleVal != null && !looksLikeDateToken(token) && doubleVal != 0.0) {
                if (doubleVal < 0) {
                    isDebit = true
                    parsedAmount = kotlin.math.abs(doubleVal)
                } else if (token.contains("+")) {
                    isCredit = true
                    parsedAmount = doubleVal
                } else {
                    // Avoid selecting year as amount if possible
                    if (doubleVal < 1900 || doubleVal > 2100) {
                        parsedAmount = doubleVal
                    }
                }
            }
        }

        if (parsedAmount == null) {
            // Try fallback matching of any float/double
            val numbers = Regex("\\d+\\.\\d+").find(line)?.value ?: Regex("\\d+").find(line)?.value
            parsedAmount = numbers?.toDoubleOrNull()
        }

        if (parsedAmount == null) return null

        // 3. Extract Date
        var dateCandidate = System.currentTimeMillis()
        for (token in wordTokens) {
            val ts = tryParseDate(token)
            if (ts != null) {
                dateCandidate = ts
                break
            }
        }

        // 4. Build Title from non-date, non-amount, non-type tokens
        val titleTokens = wordTokens.filter { token ->
            val clean = token.lowercase()
            val isAmount = token.replace(Regex("[^\\d\\.\\-]"), "").toDoubleOrNull() != null
            val isType = isTypeKeyword(token)
            val isDate = tryParseDate(token) != null || looksLikeDateToken(token)
            
            !isAmount && !isType && !isDate && clean != "cr" && clean != "dr"
        }

        var title = titleTokens.joinToString(" ").trim()
        if (title.isEmpty()) {
            title = "Bank Transaction"
        }

        val type = if (isCredit && !isDebit) {
            "INCOME"
        } else if (isDebit && !isCredit) {
            "EXPENSE"
        } else {
            if (title.lowercase().contains("refund") || title.lowercase().contains("salary")) {
                "INCOME"
            } else {
                "EXPENSE"
            }
        }

        val category = getCategoryForTitle(title)

        return ParsedRecord(
            date = dateCandidate,
            title = title,
            amount = parsedAmount,
            type = type,
            category = category,
            rawLine = line
        )
    }

    private fun isTypeKeyword(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "cr" || lower == "dr" || lower == "credit" || lower == "debit" ||
                lower == "deposit" || lower == "withdrawal"
    }

    private fun looksLikeDateToken(token: String): Boolean {
        return token.contains("/") || token.contains("-") && token.replace("-", "").toLongOrNull() != null
    }

    private fun tryParseDate(text: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "dd-MM-yyyy",
            "MM-dd-yyyy",
            "yyyy/MM/dd",
            "dd.MM.yyyy",
            "dd MMM yyyy",
            "dd MMMM yyyy"
        )
        val cleaned = text.trim().replace("[^a-zA-Z0-9\\s/\\.\\-]".toRegex(), "")
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.isLenient = false
                val date = sdf.parse(cleaned)
                if (date != null) {
                    // Check if year is reasonable
                    val yearFormat = SimpleDateFormat("yyyy", Locale.US)
                    val year = yearFormat.format(date).toInt()
                    if (year in 1990..2100) {
                        return date.time
                    }
                }
            } catch (e: Exception) {
                // Ignore and try next format
            }
        }
        return null
    }

    fun getCategoryForTitle(title: String): String {
        val lower = title.lowercase()
        return when {
            lower.contains("starbucks") || lower.contains("mcdonald") || lower.contains("cafe") || 
                    lower.contains("restaurant") || lower.contains("burger") || lower.contains("pizza") || 
                    lower.contains("food") || lower.contains("kfc") || lower.contains("eats") || 
                    lower.contains("coffee") || lower.contains("bakery") || lower.contains("dine") -> "Food"

            lower.contains("uber") || lower.contains("lyft") || lower.contains("gas") || 
                    lower.contains("fuel") || lower.contains("shell") || lower.contains("chevron") || 
                    lower.contains("exxon") || lower.contains("train") || lower.contains("metro") || 
                    lower.contains("subway") || lower.contains("bus") || lower.contains("transit") ||
                    lower.contains("cab") || lower.contains("parking") || lower.contains("toll") -> "Transportation"

            lower.contains("amazon") || lower.contains("walmart") || lower.contains("target") || 
                    lower.contains("ebay") || lower.contains("costco") || lower.contains("grocery") || 
                    lower.contains("groceries") || lower.contains("mall") || lower.contains("apparel") || 
                    lower.contains("clothing") || lower.contains("store") || lower.contains("supermarket") ||
                    lower.contains("shop") || lower.contains("nike") || lower.contains("adidas") -> "Shopping"

            lower.contains("netflix") || lower.contains("spotify") || lower.contains("disney") || 
                    lower.contains("hulu") || lower.contains("steam") || lower.contains("nintendo") || 
                    lower.contains("cinema") || lower.contains("theater") || lower.contains("movie") || 
                    lower.contains("game") || lower.contains("concert") || lower.contains("ticket") ||
                    lower.contains("playstation") || lower.contains("xbox") -> "Entertainment"

            lower.contains("electric") || lower.contains("water") || lower.contains("power") || 
                    lower.contains("gas bill") || lower.contains("internet") || lower.contains("comcast") || 
                    lower.contains("verizon") || lower.contains("at&t") || lower.contains("mobile") || 
                    lower.contains("phone") || lower.contains("utility") || lower.contains("bill") ||
                    lower.contains("subscription") -> "Utilities"

            lower.contains("salary") || lower.contains("wage") || lower.contains("dividend") || 
                    lower.contains("interest") || lower.contains("paycheck") || lower.contains("employer") || 
                    lower.contains("bonus") || lower.contains("payroll") || lower.contains("payout") -> "Salary"

            lower.contains("rent") || lower.contains("mortgage") || lower.contains("landlord") ||
                    lower.contains("apartment") || lower.contains("housing") -> "Housing"

            lower.contains("medical") || lower.contains("health") || lower.contains("doctor") ||
                    lower.contains("pharmacy") || lower.contains("hospital") || lower.contains("clinic") ||
                    lower.contains("dental") || lower.contains("insurance") -> "Health"

            else -> "Other"
        }
    }
}
