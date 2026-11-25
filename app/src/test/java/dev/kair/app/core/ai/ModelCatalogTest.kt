package dev.kair.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the plugin-extensible provider registry. */
class ModelCatalogTest {

    private fun def(id: String) = ProviderDef(
        id = id,
        displayName = id,
        defaultBaseUrl = "https://example.test/v1",
        transportId = TransportIds.OPENAI_CHAT,
    )

    @Test
    fun registerAddsProviderAndByIdResolvesIt() {
        try {
            assertTrue(ModelCatalog.register(def("test-plugin-provider")))
            assertTrue(ModelCatalog.providers.any { it.id == "test-plugin-provider" })
            assertEquals("test-plugin-provider", ModelCatalog.byId("test-plugin-provider").id)
        } finally {
            ModelCatalog.unregister("test-plugin-provider")
        }
    }

    @Test
    fun unregisterRemovesPluginProvider() {
        ModelCatalog.register(def("test-removable"))
        assertTrue(ModelCatalog.unregister("test-removable"))
        assertFalse(ModelCatalog.providers.any { it.id == "test-removable" })
    }

    @Test
    fun builtinsCannotBeUnregistered() {
        assertFalse(ModelCatalog.unregister("anthropic"))
        assertEquals("anthropic", ModelCatalog.byId("anthropic").id)
    }

    @Test
    fun registerRejectsBlankId() {
        assertFalse(ModelCatalog.register(def("")))
    }

    @Test
    fun unknownIdFallsBackToFirstRegisteredProvider() {
        assertSame(ModelCatalog.providers.first(), ModelCatalog.byId("does-not-exist"))
    }

    @Test
    fun contextWindowMatchesKnownModelFamilies() {
        assertEquals(200_000, ModelCatalog.contextWindow("claude-sonnet-4-5"))
        assertEquals(65_536, ModelCatalog.contextWindow("deepseek-chat"))
        assertEquals(128_000, ModelCatalog.contextWindow("some-unknown-model"))
    }
}
