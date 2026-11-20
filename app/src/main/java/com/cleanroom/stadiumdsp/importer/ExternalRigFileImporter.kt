package com.cleanroom.stadiumdsp.importer

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import java.io.File

enum class ExternalRigAssetKind(val label: String, val extension: String) {
    NAM_CLONE("NAM Neural Clone", ".nam"),
    AIDAX_CLONE("AIDA-X GRU Clone", ".aidax"),
    WAV_IR("Cab Impulse Response", ".wav"),
    UNKNOWN("Unsupported", ""),
}

data class ValidationResult(
    val isValid: Boolean,
    val kind: ExternalRigAssetKind,
    val message: String,
)

data class ImportedExternalFile(
    val fileName: String,
    val kind: ExternalRigAssetKind,
    val bytes: ByteArray,
)

/**
 * Pure JVM validator for SAF-imported external `.nam`, `.aidax`, and `.wav` IR payloads.
 */
object ExternalRigFileValidator {
    private const val MAX_JSON_CLONE_BYTES = 8 * 1024 * 1024  // 8 MB safety bound
    private const val MAX_WAV_IR_BYTES = 16 * 1024 * 1024     // 16 MB safety bound

    fun detectKind(fileName: String): ExternalRigAssetKind {
        val lower = fileName.trim().lowercase()
        return when {
            lower.endsWith(".nam") -> ExternalRigAssetKind.NAM_CLONE
            lower.endsWith(".aidax") -> ExternalRigAssetKind.AIDAX_CLONE
            lower.endsWith(".wav") -> ExternalRigAssetKind.WAV_IR
            else -> ExternalRigAssetKind.UNKNOWN
        }
    }

    fun validatePayload(fileName: String, bytes: ByteArray): ValidationResult {
        val kind = detectKind(fileName)
        if (kind == ExternalRigAssetKind.UNKNOWN) {
            return ValidationResult(false, kind, "Unsupported extension: $fileName (expected .nam, .aidax, or .wav)")
        }
        if (bytes.isEmpty()) {
            return ValidationResult(false, kind, "File is empty: $fileName")
        }

        return when (kind) {
            ExternalRigAssetKind.WAV_IR -> {
                if (bytes.size > MAX_WAV_IR_BYTES) {
                    return ValidationResult(false, kind, "WAV file exceeds 16 MB limit")
                }
                if (bytes.size < 44) {
                    return ValidationResult(false, kind, "WAV header truncated (< 44 bytes)")
                }
                val riff = String(bytes, 0, 4, Charsets.US_ASCII)
                val wave = String(bytes, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") {
                    return ValidationResult(false, kind, "Invalid RIFF/WAVE header in $fileName")
                }
                ValidationResult(true, kind, "Valid RIFF WAVE IR")
            }

            ExternalRigAssetKind.NAM_CLONE, ExternalRigAssetKind.AIDAX_CLONE -> {
                if (bytes.size > MAX_JSON_CLONE_BYTES) {
                    return ValidationResult(false, kind, "Clone JSON exceeds 8 MB limit")
                }
                val trimmed = bytes.decodeToString().trimStart()
                if (!trimmed.startsWith("{") || !trimmed.trimEnd().endsWith("}")) {
                    return ValidationResult(false, kind, "Malformed JSON object in $fileName")
                }
                ValidationResult(true, kind, "Valid ${kind.label} JSON")
            }

            ExternalRigAssetKind.UNKNOWN -> ValidationResult(false, kind, "Unsupported asset")
        }
    }
}

/**
 * Android Storage Access Framework (SAF) helper with graceful ActivityNotFoundException handling
 * and scoped app-external folder discovery (`Android/data/com.cleanroom.stadiumdsp/files/{clones,irs}`).
 */
object ExternalRigFileImporter {

    fun launchSafDocumentPicker(
        launcher: ActivityResultLauncher<Array<String>>,
        onError: (String) -> Unit,
    ) {
        try {
            launcher.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            onError("No Android file picker found; drop .nam/.aidax/.wav into app external files folder.")
        } catch (ex: Exception) {
            onError("Unable to open file picker: ${ex.message ?: "unknown error"}")
        }
    }

    fun readUriBytes(context: Context, uri: Uri): Result<ImportedExternalFile> = runCatching {
        val name = resolveDisplayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open input stream for $name")
        val validation = ExternalRigFileValidator.validatePayload(name, bytes)
        require(validation.isValid) { validation.message }
        ImportedExternalFile(fileName = name, kind = validation.kind, bytes = bytes)
    }

    fun listScopedQuickLoadFiles(context: Context, subFolder: String): List<File> {
        val base = context.getExternalFilesDir(null) ?: return emptyList()
        val dir = File(base, subFolder)
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()
            ?.filter { it.isFile && ExternalRigFileValidator.detectKind(it.name) != ExternalRigAssetKind.UNKNOWN }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val value = cursor.getString(idx)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported_asset"
    }
}
