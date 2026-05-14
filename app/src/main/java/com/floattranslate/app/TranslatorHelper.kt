package com.floattranslate.app

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslatorHelper {

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorCache = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    fun detectAndTranslate(text: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (text.isBlank()) {
            onError("No speech detected")
            return
        }

        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "und") {
                    // Language undetermined, still show original with note
                    onResult("[Unknown language]\n$text")
                    return@addOnSuccessListener
                }

                // If already English, just display
                if (languageCode == "en") {
                    onResult("[English]\n$text")
                    return@addOnSuccessListener
                }

                // Translate to English
                translateToEnglish(text, languageCode, onResult, onError)
            }
            .addOnFailureListener {
                onError("Language detection failed")
            }
    }

    private fun translateToEnglish(
        text: String,
        sourceLang: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val targetLang = TranslateLanguage.ENGLISH
        val sourceMlKitLang = try {
            TranslateLanguage.fromLanguageTag(sourceLang) ?: run {
                onResult("[${sourceLang.uppercase()}] $text")
                return
            }
        } catch (e: Exception) {
            onResult("[${sourceLang.uppercase()}] $text")
            return
        }

        val cacheKey = "$sourceMlKitLang->$targetLang"
        val translator = translatorCache.getOrPut(cacheKey) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceMlKitLang)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }

        // Download model if needed then translate
        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder()
            .requireWifi()
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        val langName = getLanguageName(sourceLang)
                        onResult("[$langName → EN]\n$translatedText")
                    }
                    .addOnFailureListener {
                        onError("Translation failed")
                    }
            }
            .addOnFailureListener {
                // Try without wifi restriction
                val noConditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(noConditions)
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { translatedText ->
                                val langName = getLanguageName(sourceLang)
                                onResult("[$langName → EN]\n$translatedText")
                            }
                            .addOnFailureListener {
                                onError("Translation failed. Check internet.")
                            }
                    }
                    .addOnFailureListener {
                        onError("Model download failed. Need internet.")
                    }
            }
    }

    private fun getLanguageName(code: String): String {
        return when (code) {
            "bn" -> "Bengali"
            "hi" -> "Hindi"
            "ar" -> "Arabic"
            "zh" -> "Chinese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "it" -> "Italian"
            "tr" -> "Turkish"
            "nl" -> "Dutch"
            "pl" -> "Polish"
            "ur" -> "Urdu"
            "fa" -> "Persian"
            "th" -> "Thai"
            "vi" -> "Vietnamese"
            "id" -> "Indonesian"
            "ms" -> "Malay"
            else -> code.uppercase()
        }
    }

    fun close() {
        languageIdentifier.close()
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
    }
}
