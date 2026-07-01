package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.TransactionEntity
import com.example.data.repository.TransactionRepository
import com.example.util.BankStatementParser
import com.example.util.GeminiParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val isParsing: Boolean = false,
    val parsedPreview: List<BankStatementParser.ParsedRecord> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isApiKeyConfigured: Boolean = false,
    val selectedPdfUri: android.net.Uri? = null,
    val isPdfEncrypted: Boolean = false,
    val pdfFileName: String? = null,
    val needsPasswordInput: Boolean = false
)

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TransactionRepository

    val allTransactions: StateFlow<List<TransactionEntity>>

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = TransactionRepository(database.transactionDao())
        allTransactions = repository.allTransactions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        checkApiKeyConfiguration()
    }

    private fun checkApiKeyConfiguration() {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
        val isConfigured = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"
        _importState.update { it.copy(isApiKeyConfigured = isConfigured) }
    }

    fun addManualTransaction(
        title: String,
        amount: Double,
        type: String, // "INCOME" or "EXPENSE"
        category: String,
        date: Long
    ) {
        viewModelScope.launch {
            val entity = TransactionEntity(
                title = title.trim().ifEmpty { if (type == "INCOME") "Manual Income" else "Manual Expense" },
                amount = amount,
                type = type,
                category = category,
                date = date,
                isBankImported = false
            )
            repository.insert(entity)
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.delete(transaction)
        }
    }

    fun deleteTransactionById(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun clearAllTransactions() {
        viewModelScope.launch {
            repository.clear()
        }
    }

    fun parseBankStatement(text: String, useAI: Boolean) {
        if (text.trim().isEmpty()) {
            _importState.update { it.copy(errorMessage = "Please paste some bank statement text first.") }
            return
        }

        _importState.update { it.copy(isParsing = true, errorMessage = null, successMessage = null, parsedPreview = emptyList()) }

        viewModelScope.launch {
            try {
                val records = if (useAI) {
                    GeminiParser.parseWithAI(text)
                } else {
                    BankStatementParser.parseStatementText(text)
                }

                if (records.isEmpty()) {
                    _importState.update { 
                        it.copy(
                            isParsing = false, 
                            errorMessage = if (useAI) "AI could not find any transactions in this text." else "Could not find any transactions in this format offline. Try switching to AI Smart Parse!"
                        ) 
                    }
                } else {
                    _importState.update { 
                        it.copy(
                            isParsing = false, 
                            parsedPreview = records,
                            successMessage = "Successfully parsed ${records.size} transactions!"
                        ) 
                    }
                }
            } catch (e: Exception) {
                Log.e("ExpenseViewModel", "Parsing error", e)
                _importState.update { 
                    it.copy(
                        isParsing = false, 
                        errorMessage = e.message ?: "An unexpected error occurred while parsing."
                    ) 
                }
            }
        }
    }

    fun clearPreview() {
        _importState.update { it.copy(parsedPreview = emptyList(), errorMessage = null, successMessage = null) }
    }

    fun saveImportedTransactions(selectedIndices: Set<Int>) {
        val preview = _importState.value.parsedPreview
        if (preview.isEmpty() || selectedIndices.isEmpty()) return

        viewModelScope.launch {
            val entitiesToInsert = preview.filterIndexed { index, _ -> selectedIndices.contains(index) }
                .map { record ->
                    TransactionEntity(
                        title = record.title,
                        amount = record.amount,
                        type = record.type,
                        category = record.category,
                        date = record.date,
                        isBankImported = true,
                        rawText = record.rawLine
                    )
                }

            if (entitiesToInsert.isNotEmpty()) {
                repository.insertAll(entitiesToInsert)
                _importState.update { 
                    it.copy(
                        parsedPreview = emptyList(),
                        successMessage = "Saved ${entitiesToInsert.size} transactions to your ledger!"
                    ) 
                }
            }
        }
    }

    fun dismissMessages() {
        _importState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun setPdfFile(uri: android.net.Uri?, name: String?) {
        if (uri == null) {
            _importState.update { 
                it.copy(
                    selectedPdfUri = null,
                    pdfFileName = null,
                    isPdfEncrypted = false,
                    needsPasswordInput = false
                ) 
            }
            return
        }

        val context = getApplication<Application>()
        val encrypted = com.example.util.PdfTextExtractor.isPdfEncrypted(context, uri)
        _importState.update { 
            it.copy(
                selectedPdfUri = uri,
                pdfFileName = name ?: "statement.pdf",
                isPdfEncrypted = encrypted,
                needsPasswordInput = encrypted,
                errorMessage = if (encrypted) "This statement PDF is encrypted. Please enter the password to decrypt it." else null
            ) 
        }
    }

    fun parsePdfStatement(password: String?, useAI: Boolean) {
        val uri = _importState.value.selectedPdfUri ?: return
        val context = getApplication<Application>()

        _importState.update { it.copy(isParsing = true, errorMessage = null, successMessage = null, parsedPreview = emptyList()) }

        viewModelScope.launch {
            try {
                // Extract text from the PDF
                val extractedText = com.example.util.PdfTextExtractor.extractTextFromPdf(context, uri, password)
                
                if (extractedText.trim().isEmpty()) {
                    _importState.update { 
                        it.copy(
                            isParsing = false,
                            errorMessage = "The PDF file loaded but no text content could be extracted. It might be a scanned image PDF."
                        ) 
                    }
                    return@launch
                }

                // Parse the extracted text
                val records = if (useAI) {
                    com.example.util.GeminiParser.parseWithAI(extractedText)
                } else {
                    com.example.util.BankStatementParser.parseStatementText(extractedText)
                }

                if (records.isEmpty()) {
                    _importState.update { 
                        it.copy(
                            isParsing = false, 
                            errorMessage = if (useAI) "AI could not find any transactions in this PDF's text." else "Could not find any transactions in this format offline. Try switching to AI Smart Parse!"
                        ) 
                    }
                } else {
                    _importState.update { 
                        it.copy(
                            isParsing = false, 
                            parsedPreview = records,
                            successMessage = "Successfully parsed ${records.size} transactions from PDF!"
                        ) 
                    }
                }
            } catch (e: com.example.util.PdfTextExtractor.PasswordRequiredException) {
                _importState.update { 
                    it.copy(
                        isParsing = false, 
                        needsPasswordInput = true,
                        isPdfEncrypted = true,
                        errorMessage = "This PDF statement is password-protected. Please enter the password."
                    ) 
                }
            } catch (e: com.example.util.PdfTextExtractor.IncorrectPasswordException) {
                _importState.update { 
                    it.copy(
                        isParsing = false, 
                        needsPasswordInput = true,
                        isPdfEncrypted = true,
                        errorMessage = "Incorrect password. Please verify and try again."
                    ) 
                }
            } catch (e: Exception) {
                Log.e("ExpenseViewModel", "Error parsing PDF", e)
                _importState.update { 
                    it.copy(
                        isParsing = false, 
                        errorMessage = e.message ?: "An unexpected error occurred while loading PDF."
                    ) 
                }
            }
        }
    }
}
