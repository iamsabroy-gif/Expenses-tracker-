package com.example.util

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object GeminiParser {
    private const val TAG = "GeminiParser"
    private const val MODEL_NAME = "gemini-3.5-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun parseWithAI(statementText: String): List<BankStatementParser.ParsedRecord> = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "API Key is missing or default placeholder")
            throw IllegalStateException("Gemini API key is not configured. Please add GEMINI_API_KEY to your Secrets panel.")
        }

        val prompt = """
            You are an expert bank statement parser. Parse the following bank statement text and extract all transactions.
            Identify whether each transaction is a credit (CR, deposit, refund, salary -> INCOME) or debit (DR, expense, withdrawal -> EXPENSE).
            
            Input Text:
            $statementText
            
            Return a JSON object containing a 'transactions' array.
            Format of each transaction object in JSON:
            {
              "title": "Cleaned description or merchant name",
              "amount": 12.34,
              "type": "INCOME" or "EXPENSE",
              "category": "Food", "Transportation", "Shopping", "Entertainment", "Utilities", "Salary", "Housing", "Health", or "Other",
              "dateString": "YYYY-MM-DD"
            }
        """.trimIndent()

        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.1)
            })
        }

        val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "API call failed: Status ${response.code}, Error: $errorBody")
                    throw Exception("Gemini API error (Status ${response.code})")
                }

                val responseBody = response.body?.string() ?: throw Exception("Empty response from Gemini")
                Log.d(TAG, "Response: $responseBody")

                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates") ?: throw Exception("Invalid response structure: missing 'candidates'")
                val firstCandidate = candidates.optJSONObject(0) ?: throw Exception("No candidates returned")
                val content = firstCandidate.optJSONObject("content") ?: throw Exception("Missing content")
                val parts = content.optJSONArray("parts") ?: throw Exception("Missing parts")
                val firstPart = parts.optJSONObject(0) ?: throw Exception("Missing part text")
                val textResponse = firstPart.optString("text") ?: throw Exception("Missing response text")

                val jsonResult = JSONObject(textResponse)
                val transactionsArray = jsonResult.optJSONArray("transactions") ?: JSONArray()
                
                val results = mutableListOf<BankStatementParser.ParsedRecord>()
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

                for (i in 0 until transactionsArray.length()) {
                    val txJson = transactionsArray.getJSONObject(i)
                    val title = txJson.optString("title", "AI Transaction")
                    val amount = txJson.optDouble("amount", 0.0)
                    val type = txJson.optString("type", "EXPENSE")
                    val category = txJson.optString("category", "Other")
                    val dateStr = txJson.optString("dateString", "")
                    
                    val dateTimestamp = if (dateStr.isNotEmpty()) {
                        try {
                            sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                    } else {
                        System.currentTimeMillis()
                    }

                    results.add(
                        BankStatementParser.ParsedRecord(
                            date = dateTimestamp,
                            title = title,
                            amount = amount,
                            type = type,
                            category = category,
                            rawLine = "AI parsed: $title"
                        )
                    )
                }

                results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Gemini", e)
            throw e
        }
    }
}
