@file:Suppress("LargeClass")

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderTest {
    @Test
    fun openAiCompletionsPreservesPayloadAndStreamingEvents() =
        runTest {
            val transport = FakeTransport(openAiLines())
            val provider = OpenAiCompletionsProvider(transport)
            val model = testModel("openai-completions")
            val events =
                provider
                    .stream(
                        model,
                        Context(
                            systemPrompt = "You are a test agent",
                            messages = listOf(UserMessage(UserText("hello"), 1)),
                            tools =
                                listOf(
                                    Tool(
                                        "lookup",
                                        "Look up a value",
                                        buildJsonObject { put("type", "object") },
                                    ),
                                ),
                        ),
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("You are a test agent"))
            assertTrue(body.contains("lookup"))
            assertTrue(body.contains("\"stream\":true"))
            assertEquals(
                "run-1",
                events
                    .filterIsInstance<AssistantMessageEvent.TextDelta>()
                    .first()
                    .partial.responseId,
            )
            assertTrue(events.any { it is AssistantMessageEvent.TextDelta && it.delta == "Hello" })
            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(SuccessfulStopReason.STOP, done.reason)
            assertEquals(
                "Hello world",
                done.message.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text,
            )
            assertEquals(4, done.message.usage.input)
            assertEquals(3, done.message.usage.output)
        }

    @Test
    fun openAiCompletionsAssemblesToolCallAcrossDeltas() =
        runTest {
            val transport = FakeTransport(openAiToolLines())
            val provider = OpenAiCompletionsProvider(transport)
            val events =
                provider
                    .stream(
                        testModel("openai-completions"),
                        Context(messages = listOf(UserMessage(UserText("find"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()
            val done = events.last() as AssistantMessageEvent.Done
            val call =
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
            assertEquals("call-1", call.id)
            assertEquals("lookup", call.name)
            assertEquals("value", call.arguments["query"].toString().trim('"'))
            assertEquals(SuccessfulStopReason.TOOL_USE, done.reason)
            assertTrue(events.any { it is AssistantMessageEvent.ToolCallDelta && it.delta.contains("query") })
        }

    @Test
    fun openAiCompletionsPromptCacheUsesUnicodeClampAndRetention() =
        runTest {
            val transport = FakeTransport(openAiLines())
            val model = testModel("openai-completions").copy(baseUrl = "https://api.openai.com/v1")
            OpenAiCompletionsProvider(transport)
                .stream(
                    model,
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        StreamOptions(
                            apiKey = "key",
                            cacheRetention = CacheRetention.LONG,
                            sessionId = "😀".repeat(65),
                        ),
                    ),
                ).asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"prompt_cache_key\":\"${"😀".repeat(64)}\""))
            assertTrue(body.contains("\"prompt_cache_retention\":\"24h\""))
        }

    @Test
    fun openAiCompletionsProxyOmitsUnsupportedLongCacheFields() =
        runTest {
            val transport = FakeTransport(openAiLines())
            val model =
                testModel("openai-completions").copy(
                    baseUrl = "https://proxy.test/v1",
                    compat = jsonObject("{\"supportsLongCacheRetention\":false}"),
                )
            OpenAiCompletionsProvider(transport)
                .stream(
                    model,
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        StreamOptions(apiKey = "key", cacheRetention = CacheRetention.LONG, sessionId = "session"),
                    ),
                ).asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(!body.contains("prompt_cache_key"))
            assertTrue(!body.contains("prompt_cache_retention"))
        }

    @Test
    fun openAiResponsesPromptCacheIsProviderNeutralAndNoneRemovesFields() =
        runTest {
            val transport = FakeTransport(responsesLines())
            val model = testModel("openai-responses")
            OpenAiResponsesProvider(transport)
                .stream(
                    model,
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        StreamOptions(apiKey = "key", cacheRetention = CacheRetention.LONG, sessionId = "responses"),
                    ),
                ).asFlow()
                .toList()

            var body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"store\":false"))
            assertTrue(body.contains("\"prompt_cache_key\":\"responses\""))
            assertTrue(body.contains("\"prompt_cache_retention\":\"24h\""))

            val noneTransport = FakeTransport(responsesLines())
            OpenAiResponsesProvider(noneTransport)
                .stream(
                    model,
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        StreamOptions(apiKey = "key", cacheRetention = CacheRetention.NONE, sessionId = "responses"),
                    ),
                ).asFlow()
                .toList()
            body = requireNotNull(noneTransport.request.body)
            assertTrue(!body.contains("prompt_cache_key"))
            assertTrue(!body.contains("prompt_cache_retention"))
        }

    @Test
    fun anthropicPromptCacheMarksSystemAndLastUserContent() =
        runTest {
            val transport = FakeTransport(anthropicLines())
            AnthropicMessagesProvider(transport)
                .stream(
                    testModel("anthropic-messages"),
                    Context(systemPrompt = "system", messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        StreamOptions(apiKey = "key", cacheRetention = CacheRetention.LONG),
                        reasoning = ThinkingLevel.HIGH,
                    ),
                ).asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"system\":[{"))
            assertTrue(body.contains("\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}"))
            assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":[{"))
        }

    @Test
    fun anthropicPromptCacheNoneOmitsMarkersAndLongCompatOnlyOmitsTtl() =
        runTest {
            val noneTransport = FakeTransport(anthropicLines())
            AnthropicMessagesProvider(noneTransport)
                .stream(
                    testModel("anthropic-messages"),
                    Context(systemPrompt = "system", messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(StreamOptions(apiKey = "key", cacheRetention = CacheRetention.NONE)),
                ).asFlow()
                .toList()
            val noneBody = requireNotNull(noneTransport.request.body)
            assertTrue(!noneBody.contains("cache_control"))

            val shortTransport = FakeTransport(anthropicLines())
            val model =
                testModel("anthropic-messages").copy(
                    compat = jsonObject("{\"supportsLongCacheRetention\":false}"),
                )
            AnthropicMessagesProvider(shortTransport)
                .stream(
                    model,
                    Context(systemPrompt = "system", messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(StreamOptions(apiKey = "key", cacheRetention = CacheRetention.LONG)),
                ).asFlow()
                .toList()
            val shortBody = requireNotNull(shortTransport.request.body)
            assertTrue(shortBody.contains("\"cache_control\":{\"type\":\"ephemeral\"}"))
            assertTrue(!shortBody.contains("ttl"))
        }

    @Test
    fun promptCacheEnvironmentDefaultsToLongOnlyWhenRequested() =
        runTest {
            assertEquals(
                CacheRetention.LONG,
                resolveCacheRetention(null, mapOf("PI_CACHE_RETENTION" to "long")),
            )
            assertEquals(
                CacheRetention.SHORT,
                resolveCacheRetention(null, mapOf("PI_CACHE_RETENTION" to "short")),
            )
            assertEquals(
                CacheRetention.NONE,
                resolveCacheRetention(CacheRetention.NONE, mapOf("PI_CACHE_RETENTION" to "long")),
            )
        }

    @Test
    fun mistralConversationsUsesV1CompletionsEndpoint() =
        runTest {
            val transport = FakeTransport(openAiLines())
            val provider = OpenAiCompletionsProvider(transport)
            provider
                .stream(
                    testModel("mistral-conversations"),
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(StreamOptions(apiKey = "key")),
                ).asFlow()
                .toList()
            assertEquals("https://provider.test/v1/chat/completions", transport.request.url)
            assertTrue(!requireNotNull(transport.request.body).contains("stream_options"))
        }

    @Test
    fun anthropicMessagesDecodesThinkingAndToolUse() =
        runTest {
            val transport = FakeTransport(anthropicLines())
            val provider = AnthropicMessagesProvider(transport)
            val events =
                provider
                    .stream(
                        testModel("anthropic-messages"),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "key"), reasoning = ThinkingLevel.HIGH),
                    ).asFlow()
                    .toList()
            val done = events.last() as AssistantMessageEvent.Done
            assertEquals("anthropic-1", done.message.responseId)
            assertTrue(done.message.content.any { it is ThinkingContent })
            assertEquals(
                "x",
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .arguments["query"]
                    ?.toString()
                    ?.trim('"'),
            )
            assertEquals(SuccessfulStopReason.TOOL_USE, done.reason)
        }

    @Test
    fun openAiResponsesUsesIndependentFunctionCallItems() =
        runTest {
            val transport = FakeTransport(responsesLines())
            val provider = OpenAiResponsesProvider(transport)
            val context =
                Context(
                    messages =
                        listOf(
                            AssistantMessage(
                                content =
                                    listOf(
                                        TextContent("I will look"),
                                        ToolCall(
                                            "call-1",
                                            "lookup",
                                            buildJsonObject { put("q", "x") },
                                        ),
                                    ),
                                api = "openai-responses",
                                provider = "openai",
                                model = "test-model",
                                usage = emptyUsage(),
                                stopReason = StopReason.STOP,
                                timestamp = 1,
                            ),
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "lookup",
                                content = listOf(TextContent("value")),
                                isError = false,
                                timestamp = 2,
                            ),
                        ),
                )
            val events =
                provider
                    .stream(
                        testModel("openai-responses"),
                        context,
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()
            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"type\":\"function_call\""))
            assertTrue(body.contains("\"type\":\"function_call_output\""))
            assertTrue(!body.contains("\"tool_call\""))
            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(
                "x",
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .arguments["query"]
                    ?.toString()
                    ?.trim('"'),
            )
            assertEquals(SuccessfulStopReason.TOOL_USE, done.reason)
        }

    @Test
    fun openAiResponsesUsesNativeDeferredToolSearch() =
        runTest {
            val transport = FakeTransport(responsesLines())
            val model =
                testModel("openai-responses").copy(
                    compat = jsonObject("{\"supportsToolSearch\":true}"),
                )
            val context =
                Context(
                    messages =
                        listOf(
                            AssistantMessage(
                                content = listOf(ToolCall("call-1", "base_tool", buildJsonObject {})),
                                api = "openai-responses",
                                provider = "openai-responses",
                                model = "test-model",
                                usage = emptyUsage(),
                                stopReason = StopReason.TOOL_USE,
                                timestamp = 1,
                            ),
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "base_tool",
                                content = listOf(TextContent("loaded")),
                                addedToolNames = listOf("late_tool"),
                                isError = false,
                                timestamp = 2,
                            ),
                        ),
                    tools =
                        listOf(
                            Tool("base_tool", "base", buildJsonObject { put("type", "object") }),
                            Tool("late_tool", "late", buildJsonObject { put("type", "object") }),
                        ),
                )

            OpenAiResponsesProvider(transport)
                .stream(model, context, SimpleStreamOptions(StreamOptions(apiKey = "key")))
                .asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"tools\":[{\"type\":\"function\",\"name\":\"base_tool\""))
            val topLevelTools =
                ProviderJson
                    .parseToJsonElement(body)
                    .jsonObject["tools"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            assertEquals(listOf("base_tool"), topLevelTools)
            assertTrue(body.contains("\"type\":\"tool_search_call\""))
            assertTrue(body.contains("\"type\":\"tool_search_output\""))
            assertTrue(body.contains("\"call_id\":\"pi_tool_load_c3ev19rmia53\""))
            assertTrue(body.contains("\"name\":\"late_tool\",\"description\":\"late\""))
            assertTrue(body.contains("\"defer_loading\":true"))
        }

    @Test
    fun openAiResponsesDoesNotSearchWhenCapabilityIsDisabled() =
        runTest {
            val transport = FakeTransport(responsesLines())
            val context =
                Context(
                    messages =
                        listOf(
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "base_tool",
                                content = listOf(TextContent("loaded")),
                                addedToolNames = listOf("late_tool"),
                                isError = false,
                                timestamp = 1,
                            ),
                        ),
                    tools =
                        listOf(
                            Tool("base_tool", "base", buildJsonObject {}),
                            Tool("late_tool", "late", buildJsonObject {}),
                        ),
                )

            OpenAiResponsesProvider(transport)
                .stream(testModel("openai-responses"), context, SimpleStreamOptions(StreamOptions(apiKey = "key")))
                .asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"name\":\"base_tool\""))
            assertTrue(body.contains("\"name\":\"late_tool\""))
            assertTrue(!body.contains("tool_search_call"))
            assertTrue(!body.contains("tool_search_output"))
        }

    @Test
    fun anthropicMessagesUsesDeferredReferencesAndMarksLateTools() =
        runTest {
            val transport = FakeTransport(anthropicLines())
            val model =
                testModel("anthropic-messages").copy(
                    provider = "anthropic",
                    id = "claude-opus-4-6",
                )
            val context =
                Context(
                    messages =
                        listOf(
                            AssistantMessage(
                                content = listOf(ToolCall("call-1", "base_tool", buildJsonObject {})),
                                api = "anthropic-messages",
                                provider = "anthropic",
                                model = "claude-opus-4-6",
                                usage = emptyUsage(),
                                stopReason = StopReason.TOOL_USE,
                                timestamp = 1,
                            ),
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "base_tool",
                                content = listOf(TextContent("loaded")),
                                addedToolNames = listOf("late_tool"),
                                isError = false,
                                timestamp = 2,
                            ),
                        ),
                    tools =
                        listOf(
                            Tool("base_tool", "base", buildJsonObject { put("type", "object") }),
                            Tool("late_tool", "late", buildJsonObject { put("type", "object") }),
                        ),
                )

            AnthropicMessagesProvider(transport)
                .stream(model, context, SimpleStreamOptions(StreamOptions(apiKey = "key")))
                .asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"name\":\"base_tool\""))
            assertTrue(body.contains("\"name\":\"late_tool\",\"description\":\"late\""))
            assertTrue(body.contains("\"defer_loading\":true"))
            assertTrue(body.contains("\"type\":\"tool_reference\",\"tool_name\":\"late_tool\""))
            assertTrue(body.contains("\"type\":\"text\",\"text\":\"loaded\""))
        }

    @Test
    fun anthropicMessagesKeepsOneImmediateToolWhenAllAreDeferred() =
        runTest {
            val transport = FakeTransport(anthropicLines())
            val model =
                testModel("anthropic-messages").copy(
                    provider = "anthropic",
                    id = "claude-opus-4-6",
                )
            val context =
                Context(
                    messages =
                        listOf(
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "late_tool",
                                content = listOf(TextContent("loaded")),
                                addedToolNames = listOf("late_tool"),
                                isError = false,
                                timestamp = 1,
                            ),
                        ),
                    tools = listOf(Tool("late_tool", "late", buildJsonObject {})),
                )

            AnthropicMessagesProvider(transport)
                .stream(model, context, SimpleStreamOptions(StreamOptions(apiKey = "key")))
                .asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"name\":\"late_tool\""))
            assertTrue(!body.contains("\"defer_loading\":true"))
            assertTrue(!body.contains("tool_reference"))
        }

    @Test
    fun anthropicOAuthNormalizesDeferredToolNames() =
        runTest {
            val transport = FakeTransport(anthropicLines())
            val model =
                testModel("anthropic-messages").copy(
                    provider = "anthropic",
                    id = "claude-opus-4-6",
                )
            val context =
                Context(
                    messages =
                        listOf(
                            AssistantMessage(
                                content = listOf(ToolCall("call-1", "base", buildJsonObject {})),
                                api = "anthropic-messages",
                                provider = "anthropic",
                                model = "claude-opus-4-6",
                                usage = emptyUsage(),
                                stopReason = StopReason.TOOL_USE,
                                timestamp = 1,
                            ),
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "base",
                                content = listOf(TextContent("loaded")),
                                addedToolNames = listOf("read"),
                                isError = false,
                                timestamp = 2,
                            ),
                        ),
                    tools =
                        listOf(
                            Tool("base", "base", buildJsonObject {}),
                            Tool("read", "read", buildJsonObject {}),
                        ),
                )

            AnthropicMessagesProvider(transport)
                .stream(model, context, SimpleStreamOptions(StreamOptions(apiKey = "sk-ant-oat-test")))
                .asFlow()
                .toList()

            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"name\":\"Read\""))
            assertTrue(body.contains("\"tool_name\":\"Read\""))
        }

    @Test
    fun googleGenerativeAiMapsFunctionCallAndUsage() =
        runTest {
            val transport = FakeTransport(googleLines())
            val provider = GoogleGenerativeAiProvider(transport)
            val events =
                provider
                    .stream(
                        testModel("google-generative-ai"),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()
            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(
                "Google",
                done.message.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text,
            )
            assertEquals(
                "lookup",
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .name,
            )
            assertEquals(10, done.message.usage.totalTokens)
        }

    @Test
    fun googleGenerativeAiDeduplicatesRepeatedFunctionCallChunks() =
        runTest {
            val transport = FakeTransport(googleDuplicateLines())
            val provider = GoogleGenerativeAiProvider(transport)
            val events =
                provider
                    .stream(
                        testModel("google-generative-ai"),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()
            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(
                1,
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .size,
            )
            assertEquals(
                "x",
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .arguments["query"]
                    ?.toString()
                    ?.trim('"'),
            )
        }

    @Test
    fun googleVertexUsesProjectLocationEndpointAndAccessToken() =
        runTest {
            val transport = FakeTransport(googleLines())
            GoogleGenerativeAiProvider(transport)
                .stream(
                    testModel("google-vertex").copy(baseUrl = "https://us-central1-aiplatform.googleapis.com"),
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        stream =
                            StreamOptions(
                                env =
                                    mapOf(
                                        "GOOGLE_CLOUD_PROJECT" to "demo-project",
                                        "GOOGLE_CLOUD_LOCATION" to "us-central1",
                                        "GOOGLE_CLOUD_ACCESS_TOKEN" to "token",
                                    ),
                            ),
                    ),
                ).asFlow()
                .toList()

            assertEquals(
                "https://us-central1-aiplatform.googleapis.com/v1/projects/demo-project/locations/" +
                    "us-central1/publishers/google/models/test-model:streamGenerateContent?alt=sse",
                transport.request.url,
            )
            assertEquals("Bearer token", transport.request.headers["Authorization"])
        }

    @Test
    fun azureResponsesUsesDeploymentBaseUrlCacheAndReasoningOptions() =
        runTest {
            val transport = FakeTransport(responsesLines())
            val events =
                OpenAiResponsesProvider(transport)
                    .stream(
                        testModel("azure-openai-responses").copy(baseUrl = ""),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(
                            stream =
                                StreamOptions(
                                    apiKey = "azure-key",
                                    sessionId = "x".repeat(67),
                                    maxTokens = 4,
                                    azureBaseUrl = "https://resource.openai.azure.com/openai",
                                    azureApiVersion = "v1",
                                    azureDeploymentName = "prod-gpt",
                                    azureReasoningSummary = "detailed",
                                ),
                            reasoning = ThinkingLevel.HIGH,
                        ),
                    ).asFlow()
                    .toList()

            assertEquals(
                "https://resource.openai.azure.com/openai/v1/responses?api-version=v1",
                transport.request.url,
            )
            assertEquals("Bearer azure-key", transport.request.headers["Authorization"])
            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"model\":\"prod-gpt\""))
            assertTrue(body.contains("\"store\":false"))
            assertTrue(body.contains("\"prompt_cache_key\":\"${"x".repeat(64)}\""))
            assertTrue(body.contains("\"max_output_tokens\":16"))
            assertTrue(body.contains("\"summary\":\"detailed\""))
            assertTrue(body.contains("reasoning.encrypted_content"))
            assertTrue(events.last() is AssistantMessageEvent.Done)
        }

    @Test
    fun azureResponsesResolvesResourceAndDeploymentEnvironment() =
        runTest {
            val transport = FakeTransport(responsesLines())
            OpenAiResponsesProvider(transport)
                .stream(
                    testModel("azure-openai-responses").copy(baseUrl = ""),
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        stream =
                            StreamOptions(
                                apiKey = "key",
                                env =
                                    mapOf(
                                        "AZURE_OPENAI_RESOURCE_NAME" to "demo-resource",
                                        "AZURE_OPENAI_API_VERSION" to "2025-01-01",
                                        "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "test-model=deployment-a,other=ignored",
                                    ),
                            ),
                    ),
                ).asFlow()
                .toList()

            assertEquals(
                "https://demo-resource.openai.azure.com/openai/v1/responses?api-version=2025-01-01",
                transport.request.url,
            )
            assertTrue(requireNotNull(transport.request.body).contains("\"model\":\"deployment-a\""))
        }

    @Test
    fun azureResponsesReportsInvalidBaseUrlAsProviderError() =
        runTest {
            val events =
                OpenAiResponsesProvider(FakeTransport(responsesLines()))
                    .stream(
                        testModel("azure-openai-responses").copy(baseUrl = "not-a-url"),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "key")),
                    ).asFlow()
                    .toList()

            val error = events.last() as AssistantMessageEvent.Error
            assertEquals(FailureStopReason.ERROR, error.reason)
            assertTrue(
                error.error.errorMessage
                    .orEmpty()
                    .contains("Invalid Azure OpenAI base URL"),
            )
        }

    @Test
    fun bedrockConverseBuildsPayloadUsesBearerAuthAndDecodesToolStream() =
        runTest {
            val transport = FakeTransport(bedrockJsonLines())
            val model =
                testModel("bedrock-converse-stream").copy(
                    provider = "amazon-bedrock",
                    baseUrl = "https://bedrock-runtime.us-west-2.amazonaws.com",
                    reasoning = false,
                )
            val events =
                BedrockConverseProvider(transport)
                    .stream(
                        model,
                        Context(
                            systemPrompt = "system",
                            messages = listOf(UserMessage(UserText("hello"), 1)),
                            tools =
                                listOf(
                                    Tool(
                                        "lookup",
                                        "Look up a value",
                                        buildJsonObject { put("type", "object") },
                                    ),
                                ),
                        ),
                        SimpleStreamOptions(StreamOptions(apiKey = "bedrock-token", bedrockToolChoice = "auto")),
                    ).asFlow()
                    .toList()

            assertEquals(
                "https://bedrock-runtime.us-west-2.amazonaws.com/model/test-model/converse-stream",
                transport.request.url,
            )
            assertEquals("Bearer bedrock-token", transport.request.headers["authorization"])
            val body = requireNotNull(transport.request.body)
            assertTrue(body.contains("\"system\""))
            assertTrue(body.contains("\"toolSpec\""))
            assertTrue(body.contains("\"toolChoice\":{\"auto\":{}}"))
            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(SuccessfulStopReason.TOOL_USE, done.reason)
            assertEquals(
                "hello",
                done.message.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text,
            )
            assertEquals(
                "lookup",
                done.message.content
                    .filterIsInstance<ToolCall>()
                    .single()
                    .name,
            )
            assertEquals(7, done.message.usage.totalTokens)
        }

    @Test
    fun bedrockConverseSignsSigV4RequestWithScopedEnvironment() =
        runTest {
            val transport = FakeTransport(bedrockJsonLines(textOnly = true))
            BedrockConverseProvider(transport)
                .stream(
                    testModel("bedrock-converse-stream").copy(
                        provider = "amazon-bedrock",
                        baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
                        reasoning = false,
                    ),
                    Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                    SimpleStreamOptions(
                        StreamOptions(
                            env =
                                mapOf(
                                    "AWS_ACCESS_KEY_ID" to "AKIDEXAMPLE",
                                    "AWS_SECRET_ACCESS_KEY" to "secret",
                                    "AWS_REGION" to "us-east-1",
                                    "AWS_AMZ_DATE" to "20260101T000000Z",
                                ),
                        ),
                    ),
                ).asFlow()
                .toList()

            val authorization = requireNotNull(transport.request.headers["authorization"])
            assertTrue(
                authorization.startsWith(
                    "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260101/us-east-1/bedrock/aws4_request",
                ),
            )
            assertTrue(authorization.contains("SignedHeaders="))
            assertTrue(authorization.matches(Regex(".*Signature=[0-9a-f]{64}")))
            assertEquals(64, transport.request.headers["x-amz-content-sha256"]?.length)
            assertEquals("20260101T000000Z", transport.request.headers["x-amz-date"])
        }

    @Test
    fun bedrockConverseDecodesAwsEventStreamFrames() =
        runTest {
            val transport =
                FakeTransport(
                    responseLines = emptyList(),
                    responseBytes =
                        listOf(
                            bedrockEventFrame("messageStart", "{\"role\":\"assistant\"}"),
                            bedrockEventFrame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"binary\"}}",
                            ),
                            bedrockEventFrame("messageStop", "{\"stopReason\":\"end_turn\"}"),
                        ),
                )
            val events =
                BedrockConverseProvider(transport)
                    .stream(
                        testModel("bedrock-converse-stream").copy(
                            provider = "amazon-bedrock",
                            baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
                            reasoning = false,
                        ),
                        Context(messages = listOf(UserMessage(UserText("hello"), 1))),
                        SimpleStreamOptions(StreamOptions(apiKey = "token")),
                    ).asFlow()
                    .toList()

            val done = events.last() as AssistantMessageEvent.Done
            assertEquals(
                "binary",
                done.message.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text,
            )
        }

    @Test
    fun openRouterImageProviderDecodesImageAndUsage() =
        runTest {
            val transport =
                FakeTransport(
                    listOf(
                        wireLine(
                            "{\"id\":\"image-1\",\"choices\":[{\"message\":{\"content\":\"done\",",
                            "\"images\":[{\"image_url\":",
                            "{\"url\":\"data:image/png;base64,AAAA\"}}]}}],\"usage\":{\"prompt_tokens\":10,",
                            "\"completion_tokens\":2,",
                            "\"prompt_tokens_details\":{\"cached_tokens\":3,\"cache_write_tokens\":1}}}",
                        ),
                    ),
                )
            val result =
                OpenRouterImageProvider(transport).generateImages(
                    ImagesModel(
                        id = "test-image",
                        name = "Test image",
                        api = "openrouter-images",
                        provider = "openrouter",
                        baseUrl = "https://provider.test/v1",
                        input = listOf(ModelInput.TEXT),
                        output = listOf(ModelInput.TEXT, ModelInput.IMAGE),
                        cost = ModelCost(1.0, 2.0, 3.0, 4.0),
                    ),
                    ImagesContext(listOf(TextContent("draw"))),
                    ImagesOptions(apiKey = "key"),
                )
            assertEquals("image-1", result.responseId)
            assertEquals(2, result.output.size)
            val usage = requireNotNull(result.usage)
            assertEquals(7, usage.input)
            assertEquals(2, usage.cacheRead)
            assertEquals(1, usage.cacheWrite)
        }

    private fun testModel(api: Api): Model =
        Model(
            id = "test-model",
            name = "Test",
            api = api,
            provider = api,
            baseUrl = "https://provider.test/v1",
            reasoning = true,
            input = listOf(ModelInput.TEXT, ModelInput.IMAGE),
            cost = ModelCost(1.0, 2.0, 3.0, 4.0),
            contextWindow = 128_000,
            maxTokens = 8_192,
        )

    private fun jsonObject(value: String): JsonObject =
        ProviderJson.parseToJsonElement(value) as? JsonObject
            ?: error("Expected a JSON object")

    private class FakeTransport(
        private val responseLines: List<String>,
        private val responseBytes: List<ByteArray>? = null,
    ) : HttpTransport {
        lateinit var request: HttpRequest

        override suspend fun execute(request: HttpRequest): HttpResponse {
            this.request = request
            return HttpResponse(
                200,
                mapOf("x-request-id" to "test"),
                flowOf(*responseLines.toTypedArray()),
                responseBytes?.let { bytes -> flowOf(*bytes.toTypedArray()) },
            )
        }
    }

    private fun wireLine(vararg parts: String): String = parts.joinToString("")

    private fun openAiLines(): List<String> =
        listOf(
            "data: {\"id\":\"run-1\",\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "",
            "data: {\"id\":\"run-1\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
            "",
            "data: {\"id\":\"run-1\",\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
            "",
            wireLine(
                "data: {\"id\":\"run-1\",\"choices\":[{\"delta\":{},",
                "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,",
                "\"completion_tokens\":3}}",
            ),
            "",
            "data: [DONE]",
            "",
        )

    private fun openAiToolLines(): List<String> =
        listOf(
            wireLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",",
                "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}",
            ),
            "",
            wireLine(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":",
                "{\"arguments\":\"\\\"value\\\"}\"}}]}}]}",
            ),
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]} ",
            "",
            "data: [DONE]",
            "",
        )

    private fun anthropicLines(): List<String> =
        listOf(
            "event: message_start",
            wireLine(
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"anthropic-1\",",
                "\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}}",
            ),
            "",
            "event: content_block_start",
            wireLine(
                "data: {\"type\":\"content_block_start\",\"index\":0,",
                "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"plan\",\"signature\":\"sig\"}}",
            ),
            "",
            "event: content_block_delta",
            wireLine(
                "data: {\"type\":\"content_block_delta\",\"index\":0,",
                "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\" more\"}}",
            ),
            "",
            "event: content_block_start",
            wireLine(
                "data: {\"type\":\"content_block_start\",\"index\":1,",
                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"lookup\",\"input\":{}}}",
            ),
            "",
            "event: content_block_delta",
            wireLine(
                "data: {\"type\":\"content_block_delta\",\"index\":1,",
                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"x\\\"}\"}}",
            ),
            "",
            "event: message_delta",
            wireLine(
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},",
                "\"usage\":{\"input_tokens\":2,\"output_tokens\":4}}",
            ),
            "",
        )

    private fun googleLines(): List<String> =
        listOf(
            "data: {\"responseId\":\"google-1\",\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Google\"}]}}]}",
            "",
            wireLine(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"lookup\",",
                "\"args\":{\"query\":\"x\"}}}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":",
                "{\"promptTokenCount\":4,\"candidatesTokenCount\":6,\"totalTokenCount\":10}}",
            ),
            "",
        )

    private fun googleDuplicateLines(): List<String> =
        listOf(
            wireLine(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"id\":\"call-1\",",
                "\"name\":\"lookup\",\"args\":{\"query\":\"x\"}}}]},\"finishReason\":\"STOP\"}]} ",
            ),
            "",
            wireLine(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"id\":\"call-1\",",
                "\"name\":\"lookup\",\"args\":{\"query\":\"x\"}}}]}}]}",
            ),
            "",
        )

    private fun responsesLines(): List<String> =
        listOf(
            "event: response.created",
            "data: {\"response\":{\"id\":\"response-1\"}}",
            "",
            "event: response.output_text.delta",
            "data: {\"output_index\":0,\"delta\":\"value\"}",
            "",
            "event: response.output_item.added",
            wireLine(
                "data: {\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"fc-1\",",
                "\"call_id\":\"call-1\",\"name\":\"lookup\"}}",
            ),
            "",
            "event: response.function_call_arguments.delta",
            "data: {\"output_index\":1,\"call_id\":\"call-1\",\"delta\":\"{\\\"query\\\":\\\"x\\\"}\"}",
            "",
            "event: response.function_call_arguments.done",
            "data: {\"output_index\":1,\"call_id\":\"call-1\",\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\"}",
            "",
            "event: response.completed",
            wireLine(
                "data: {\"response\":{\"id\":\"response-1\",\"status\":\"completed\",\"usage\":",
                "\"input_tokens\":4,\"output_tokens\":3,\"total_tokens\":7}}}",
            ),
            "",
        )

    private fun bedrockJsonLines(textOnly: Boolean = false): List<String> =
        if (textOnly) {
            listOf(
                "{\"messageStart\":{\"role\":\"assistant\"}}",
                "{\"contentBlockDelta\":{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello\"}}}",
                "{\"messageStop\":{\"stopReason\":\"end_turn\"}}",
                "{\"metadata\":{\"usage\":{\"inputTokens\":2,\"outputTokens\":1,\"totalTokens\":3}}}",
            )
        } else {
            listOf(
                "{\"messageStart\":{\"role\":\"assistant\"}}",
                "{\"contentBlockDelta\":{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello\"}}}",
                wireLine(
                    "{\"contentBlockStart\":{\"contentBlockIndex\":1,\"start\":{",
                    "\"toolUse\":{\"toolUseId\":\"tool-1\",\"name\":\"lookup\"}}}}",
                ),
                wireLine(
                    "{\"contentBlockDelta\":{\"contentBlockIndex\":1,\"delta\":{",
                    "\"toolUse\":{\"input\":\"{\\\"query\\\":\\\"x\\\"}\"}}}}",
                ),
                "{\"messageStop\":{\"stopReason\":\"tool_use\"}}",
                "{\"metadata\":{\"usage\":{\"inputTokens\":4,\"outputTokens\":3,\"totalTokens\":7}}}",
            )
        }

    private fun bedrockEventFrame(
        type: String,
        payload: String,
    ): ByteArray {
        val name = ":event-type".encodeToByteArray()
        val value = type.encodeToByteArray()
        val headers =
            byteArrayOf(name.size.toByte()) +
                name +
                byteArrayOf(7, (value.size ushr 8).toByte(), value.size.toByte()) +
                value
        val body = payload.encodeToByteArray()
        val totalLength = 12 + headers.size + body.size + 4
        return ByteArray(totalLength).also { frame ->
            writeBigEndianInt(frame, 0, totalLength)
            writeBigEndianInt(frame, 4, headers.size)
            headers.copyInto(frame, 12)
            body.copyInto(frame, 12 + headers.size)
        }
    }

    private fun writeBigEndianInt(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
