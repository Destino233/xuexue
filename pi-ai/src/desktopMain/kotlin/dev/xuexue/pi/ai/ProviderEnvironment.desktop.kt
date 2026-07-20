package dev.xuexue.pi.ai

actual fun ambientProviderEnvironment(): Map<String, String> = System.getenv()
