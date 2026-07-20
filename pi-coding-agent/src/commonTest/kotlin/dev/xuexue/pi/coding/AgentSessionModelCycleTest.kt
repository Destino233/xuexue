package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AuthCredential
import dev.xuexue.pi.ai.InMemoryAuthStore
import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.coding.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionModelCycleTest {
    @Test
    fun scopedCycleFiltersUnauthenticatedModelsAndPreservesOrder() {
        val first = testModel(id = "first", provider = "provider-one")
        val unavailable = testModel(id = "unavailable", provider = "provider-three")
        val second = testModel(id = "second", provider = "provider-two")
        val registry =
            ModelRegistry(
                authStore =
                    InMemoryAuthStore(
                        initial =
                            mapOf(
                                "provider-one" to AuthCredential.ApiKey("one"),
                                "provider-two" to AuthCredential.ApiKey("two"),
                            ),
                    ),
                builtInModels = listOf(first, unavailable, second),
            )
        val harness =
            AgentSessionHarness(
                sessionManager = SessionManager.inMemory("/workspace"),
                modelRegistry = registry,
                scopedModels = listOf(ScopedModel(first), ScopedModel(unavailable), ScopedModel(second)),
            )

        val forward = harness.session.cycleModel()

        assertEquals("second", forward?.model?.id)
        assertEquals(true, forward?.isScoped)
        assertEquals("second", harness.session.model.id)
        assertEquals(
            "first",
            harness.session
                .cycleModel(ModelCycleDirection.BACKWARD)
                ?.model
                ?.id,
        )
    }

    @Test
    fun missingCurrentModelStartsAtScopeIndexZeroBeforeApplyingDirection() {
        val first = testModel(id = "first", provider = "provider-one")
        val second = testModel(id = "second", provider = "provider-two")
        val outside = testModel(id = "outside", provider = "provider-one")
        val registry =
            ModelRegistry(
                authStore =
                    InMemoryAuthStore(
                        initial =
                            mapOf(
                                "provider-one" to AuthCredential.ApiKey("one"),
                                "provider-two" to AuthCredential.ApiKey("two"),
                            ),
                    ),
                builtInModels = listOf(first, second),
            )
        val harness =
            AgentSessionHarness(
                sessionManager = SessionManager.inMemory("/workspace"),
                modelRegistry = registry,
                scopedModels = listOf(ScopedModel(first), ScopedModel(second)),
            )
        harness.session.setModel(outside)

        assertEquals(
            "second",
            harness.session
                .cycleModel()
                ?.model
                ?.id,
        )
    }

    @Test
    fun scopedThinkingLevelOverridesThenAnUnspecifiedEntryInheritsIt() {
        val first = testModel(id = "first", provider = "provider-one")
        val second = testModel(id = "second", provider = "provider-two")
        val registry =
            ModelRegistry(
                authStore =
                    InMemoryAuthStore(
                        initial =
                            mapOf(
                                "provider-one" to AuthCredential.ApiKey("one"),
                                "provider-two" to AuthCredential.ApiKey("two"),
                            ),
                    ),
                builtInModels = listOf(first, second),
            )
        val harness =
            AgentSessionHarness(
                sessionManager = SessionManager.inMemory("/workspace"),
                modelRegistry = registry,
                scopedModels =
                    listOf(
                        ScopedModel(first),
                        ScopedModel(second, ModelThinkingLevel.HIGH),
                    ),
            )

        harness.session.cycleModel()
        assertEquals(ModelThinkingLevel.HIGH, harness.session.thinkingLevel)

        val inherited = harness.session.cycleModel(ModelCycleDirection.BACKWARD)
        assertEquals(ModelThinkingLevel.HIGH, inherited?.thinkingLevel)
        assertEquals(ModelThinkingLevel.HIGH, harness.session.thinkingLevel)
    }

    @Test
    fun onlyOneAuthenticatedScopedModelProducesNoResult() {
        val first = testModel(id = "first", provider = "provider-one")
        val second = testModel(id = "second", provider = "provider-two")
        val registry =
            ModelRegistry(
                authStore = InMemoryAuthStore(initial = mapOf("provider-one" to AuthCredential.ApiKey("one"))),
                builtInModels = listOf(first, second),
            )
        val harness =
            AgentSessionHarness(
                sessionManager = SessionManager.inMemory("/workspace"),
                modelRegistry = registry,
                scopedModels = listOf(ScopedModel(first), ScopedModel(second)),
            )

        assertNull(harness.session.cycleModel())
    }
}
