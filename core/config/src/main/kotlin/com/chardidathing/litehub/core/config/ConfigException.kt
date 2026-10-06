package com.chardidathing.litehub.core.config

// message is shown on screen, so keep it lowercase and say what's wrong
class ConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)
