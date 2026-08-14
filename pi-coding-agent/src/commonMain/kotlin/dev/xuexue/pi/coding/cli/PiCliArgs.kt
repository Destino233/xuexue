package dev.xuexue.pi.coding.cli

enum class PiCliMode {
    TEXT,
    JSON,
    RPC,
}

data class PiCliArgs(
    val provider: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
    val systemPrompt: String? = null,
    val appendSystemPrompt: List<String> = emptyList(),
    val thinking: String? = null,
    val continueSession: Boolean = false,
    val resume: Boolean = false,
    val help: Boolean = false,
    val version: Boolean = false,
    val mode: PiCliMode? = null,
    val name: String? = null,
    val noSession: Boolean = false,
    val session: String? = null,
    val sessionId: String? = null,
    val fork: String? = null,
    val sessionDir: String? = null,
    val models: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val excludeTools: List<String> = emptyList(),
    val noTools: Boolean = false,
    val noBuiltinTools: Boolean = false,
    val extensions: List<String> = emptyList(),
    val noExtensions: Boolean = false,
    val print: Boolean = false,
    val export: String? = null,
    val noSkills: Boolean = false,
    val skills: List<String> = emptyList(),
    val promptTemplates: List<String> = emptyList(),
    val noPromptTemplates: Boolean = false,
    val themes: List<String> = emptyList(),
    val noThemes: Boolean = false,
    val noContextFiles: Boolean = false,
    val listModels: String? = null,
    val offline: Boolean = false,
    val verbose: Boolean = false,
    val projectTrustOverride: Boolean? = null,
    val messages: List<String> = emptyList(),
    val fileArgs: List<String> = emptyList(),
    val unknownFlags: Map<String, String> = emptyMap(),
    val diagnostics: List<PiCliDiagnostic> = emptyList(),
)

data class PiCliDiagnostic(
    val type: String,
    val message: String,
)

private val VALID_THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/**
 * Port of Pi's cli/args.ts parseArgs: full flag surface with unknown-flag
 * capture and diagnostics. printHelp is TUI text and excluded.
 */
fun parsePiArgs(args: List<String>): PiCliArgs {
    var provider: String? = null
    var model: String? = null
    var apiKey: String? = null
    var systemPrompt: String? = null
    val appendSystemPrompt = mutableListOf<String>()
    var thinking: String? = null
    var continueSession = false
    var resume = false
    var help = false
    var version = false
    var mode: PiCliMode? = null
    var name: String? = null
    var noSession = false
    var session: String? = null
    var sessionId: String? = null
    var fork: String? = null
    var sessionDir: String? = null
    val models = mutableListOf<String>()
    val tools = mutableListOf<String>()
    val excludeTools = mutableListOf<String>()
    var noTools = false
    var noBuiltinTools = false
    val extensions = mutableListOf<String>()
    var noExtensions = false
    var print = false
    var export: String? = null
    var noSkills = false
    val skills = mutableListOf<String>()
    val promptTemplates = mutableListOf<String>()
    var noPromptTemplates = false
    val themes = mutableListOf<String>()
    var noThemes = false
    var noContextFiles = false
    var listModels: String? = null
    var offline = false
    var verbose = false
    var projectTrustOverride: Boolean? = null
    val messages = mutableListOf<String>()
    val fileArgs = mutableListOf<String>()
    val unknownFlags = linkedMapOf<String, String>()
    val diagnostics = mutableListOf<PiCliDiagnostic>()

    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg == "--help" || arg == "-h" -> help = true
            arg == "--version" || arg == "-v" -> version = true
            arg == "--mode" && index + 1 < args.size -> {
                val value = args[++index]
                mode =
                    when (value) {
                        "text" -> PiCliMode.TEXT
                        "json" -> PiCliMode.JSON
                        "rpc" -> PiCliMode.RPC
                        else -> null
                    }
            }
            arg == "--continue" || arg == "-c" -> continueSession = true
            arg == "--resume" || arg == "-r" -> resume = true
            arg == "--provider" && index + 1 < args.size -> provider = args[++index]
            arg == "--model" && index + 1 < args.size -> model = args[++index]
            arg == "--api-key" && index + 1 < args.size -> apiKey = args[++index]
            arg == "--system-prompt" && index + 1 < args.size -> systemPrompt = args[++index]
            arg == "--append-system-prompt" && index + 1 < args.size -> appendSystemPrompt += args[++index]
            arg == "--name" || arg == "-n" -> {
                if (index + 1 < args.size) {
                    name = args[++index]
                } else {
                    diagnostics += PiCliDiagnostic("error", "--name requires a value")
                }
            }
            arg == "--no-session" -> noSession = true
            arg == "--session" && index + 1 < args.size -> session = args[++index]
            arg == "--session-id" && index + 1 < args.size -> sessionId = args[++index]
            arg == "--fork" && index + 1 < args.size -> fork = args[++index]
            arg == "--session-dir" && index + 1 < args.size -> sessionDir = args[++index]
            arg == "--models" && index + 1 < args.size -> {
                models += args[++index].split(",").map(String::trim).filter(String::isNotEmpty)
            }
            arg == "--no-tools" || arg == "-nt" -> noTools = true
            arg == "--no-builtin-tools" || arg == "-nbt" -> noBuiltinTools = true
            (arg == "--tools" || arg == "-t") && index + 1 < args.size -> {
                tools += args[++index].split(",").map(String::trim).filter(String::isNotEmpty)
            }
            (arg == "--exclude-tools" || arg == "-xt") && index + 1 < args.size -> {
                excludeTools += args[++index].split(",").map(String::trim).filter(String::isNotEmpty)
            }
            arg == "--thinking" && index + 1 < args.size -> {
                val level = args[++index]
                if (level in VALID_THINKING_LEVELS) {
                    thinking = level
                } else {
                    diagnostics += PiCliDiagnostic(
                        "warning",
                        "Invalid thinking level \"$level\". Valid values: ${VALID_THINKING_LEVELS.joinToString(", ")}",
                    )
                }
            }
            arg == "--print" || arg == "-p" -> {
                print = true
                val next = args.getOrNull(index + 1)
                if (next != null && !next.startsWith("@") && (!next.startsWith("-") || next.startsWith("---"))) {
                    messages += next
                    index++
                }
            }
            arg == "--export" && index + 1 < args.size -> export = args[++index]
            (arg == "--extension" || arg == "-e") && index + 1 < args.size -> extensions += args[++index]
            arg == "--no-extensions" || arg == "-ne" -> noExtensions = true
            arg == "--skill" && index + 1 < args.size -> skills += args[++index]
            arg == "--prompt-template" && index + 1 < args.size -> promptTemplates += args[++index]
            arg == "--theme" && index + 1 < args.size -> themes += args[++index]
            arg == "--no-skills" || arg == "-ns" -> noSkills = true
            arg == "--no-prompt-templates" || arg == "-np" -> noPromptTemplates = true
            arg == "--no-themes" -> noThemes = true
            arg == "--no-context-files" || arg == "-nc" -> noContextFiles = true
            arg == "--list-models" -> {
                val next = args.getOrNull(index + 1)
                if (next != null && !next.startsWith("-") && !next.startsWith("@")) {
                    listModels = args[++index]
                } else {
                    listModels = ""
                }
            }
            arg == "--verbose" -> verbose = true
            arg == "--approve" || arg == "-a" -> projectTrustOverride = true
            arg == "--no-approve" || arg == "-na" -> projectTrustOverride = false
            arg == "--offline" -> offline = true
            arg.startsWith("@") -> fileArgs += arg.substring(1)
            arg.startsWith("--") -> {
                val eqIndex = arg.indexOf('=')
                if (eqIndex != -1) {
                    unknownFlags[arg.substring(2, eqIndex)] = arg.substring(eqIndex + 1)
                } else {
                    val flagName = arg.substring(2)
                    val next = args.getOrNull(index + 1)
                    if (next != null && !next.startsWith("-") && !next.startsWith("@")) {
                        unknownFlags[flagName] = next
                        index++
                    } else {
                        unknownFlags[flagName] = "true"
                    }
                }
            }
            arg.startsWith("-") && !arg.startsWith("--") -> {
                diagnostics += PiCliDiagnostic("error", "Unknown option: $arg")
            }
            else -> messages += arg
        }
        index++
    }

    return PiCliArgs(
        provider = provider,
        model = model,
        apiKey = apiKey,
        systemPrompt = systemPrompt,
        appendSystemPrompt = appendSystemPrompt,
        thinking = thinking,
        continueSession = continueSession,
        resume = resume,
        help = help,
        version = version,
        mode = mode,
        name = name,
        noSession = noSession,
        session = session,
        sessionId = sessionId,
        fork = fork,
        sessionDir = sessionDir,
        models = models,
        tools = tools,
        excludeTools = excludeTools,
        noTools = noTools,
        noBuiltinTools = noBuiltinTools,
        extensions = extensions,
        noExtensions = noExtensions,
        print = print,
        export = export,
        noSkills = noSkills,
        skills = skills,
        promptTemplates = promptTemplates,
        noPromptTemplates = noPromptTemplates,
        themes = themes,
        noThemes = noThemes,
        noContextFiles = noContextFiles,
        listModels = listModels,
        offline = offline,
        verbose = verbose,
        projectTrustOverride = projectTrustOverride,
        messages = messages,
        fileArgs = fileArgs,
        unknownFlags = unknownFlags,
        diagnostics = diagnostics,
    )
}

/** Port of Pi's cli/initial-message.ts buildInitialMessage. */
data class InitialMessageInput(
    val messages: List<String>,
    val fileText: String? = null,
    val stdinContent: String? = null,
)

data class InitialMessageResult(
    val initialMessage: String? = null,
    /** The remaining CLI messages after the first was consumed. */
    val remainingMessages: List<String>,
)

fun buildInitialMessage(input: InitialMessageInput): InitialMessageResult {
    val parts = mutableListOf<String>()
    input.stdinContent?.let(parts::add)
    input.fileText?.let(parts::add)
    val remaining = input.messages.toMutableList()
    if (remaining.isNotEmpty()) {
        parts += remaining.removeAt(0)
    }
    return InitialMessageResult(
        initialMessage = parts.takeIf(List<String>::isNotEmpty)?.joinToString(""),
        remainingMessages = remaining,
    )
}