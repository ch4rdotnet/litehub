package com.chardidathing.litehub.core.config

// message is shown on screen, so keep it lowercase and say what's wrong
class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

// kotlinx messages run to several lines of advice for developers, keep the line that says what broke
internal fun Throwable.summary(): String =
    message.orEmpty().lineSequence().first().trim().replaceFirstChar { it.lowercase() }
