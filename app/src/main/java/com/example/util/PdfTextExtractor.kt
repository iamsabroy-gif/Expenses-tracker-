package com.example.util

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.io.IOException

object PdfTextExtractor {

    class PasswordRequiredException : IOException("Password is required to decrypt this PDF statement.")
    class IncorrectPasswordException : IOException("The password provided is incorrect.")

    /**
     * Checks if a PDF file is encrypted without extracting its text.
     * Returns true if encrypted, false otherwise.
     */
    fun isPdfEncrypted(context: Context, fileUri: Uri): Boolean {
        PDFBoxResourceLoader.init(context)
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        return try {
            inputStream = context.contentResolver.openInputStream(fileUri) ?: return false
            document = PDDocument.load(inputStream)
            // If it loaded successfully with no password, check if it's encrypted
            document.isEncrypted
        } catch (e: InvalidPasswordException) {
            true
        } catch (e: Exception) {
            false
        } finally {
            try { document?.close() } catch (ignored: Exception) {}
            try { inputStream?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Extracts full text from a PDF, supporting password decryption.
     */
    fun extractTextFromPdf(context: Context, fileUri: Uri, password: String? = null): String {
        PDFBoxResourceLoader.init(context)
        val contentResolver = context.contentResolver
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        try {
            inputStream = contentResolver.openInputStream(fileUri) ?: throw IOException("Could not open file input stream")
            
            document = if (!password.isNullOrEmpty()) {
                try {
                    PDDocument.load(inputStream, password)
                } catch (e: InvalidPasswordException) {
                    throw IncorrectPasswordException()
                }
            } else {
                try {
                    PDDocument.load(inputStream)
                } catch (e: InvalidPasswordException) {
                    throw PasswordRequiredException()
                }
            }

            if (document.isEncrypted && !document.isAllSecurityToBeRemoved) {
                // Double check if decrypted successfully
                if (password.isNullOrEmpty()) {
                    throw PasswordRequiredException()
                }
            }

            val stripper = PDFTextStripper()
            return stripper.getText(document)
        } finally {
            try { document?.close() } catch (ignored: Exception) {}
            try { inputStream?.close() } catch (ignored: Exception) {}
        }
    }
}
