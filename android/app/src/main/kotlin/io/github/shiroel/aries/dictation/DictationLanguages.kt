package io.github.shiroel.aries.dictation

/**
 * Groq large-v3 supports 99+ languages:
 * https://console.groq.com/docs/model/whisper-large-v3
 * Full 100-language list from the model author's official Whisper tokenizer:
 * https://github.com/openai/whisper/blob/main/whisper/tokenizer.py (LANGUAGES).
 * Keep Whisper's API codes, including `jw`, `haw`, and large-v3's `yue`.
 */
val dictationLanguageCodes: List<String> = listOf(
    "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
    "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
    "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
    "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
    "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
    "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
    "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
    "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
    "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
    "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue",
)
