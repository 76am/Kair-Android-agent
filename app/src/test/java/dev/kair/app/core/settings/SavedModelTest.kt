package dev.kair.app.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedModelTest {

    private fun model(maxTokens: Int?) = SavedModel(
        id = "m1",
        name = "test",
        providerId = "openai",
        model = "test-model",
        maxTokens = maxTokens,
    )

    @Test
    fun `blank output limit falls back to 8192`() {
        assertEquals(8192, model(null).effectiveMaxTokens())
    }

    @Test
    fun `explicit output limit wins over the default`() {
        assertEquals(4096, model(4096).effectiveMaxTokens())
    }
}
