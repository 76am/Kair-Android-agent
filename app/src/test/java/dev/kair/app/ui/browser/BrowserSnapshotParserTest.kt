package dev.kair.app.ui.browser

import dev.kair.app.core.browser.BrowserActionException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSnapshotParserTest {

    private fun wrap(json: String): String = JsonPrimitive(json).toString()

    @Test
    fun `snapshot parses page text and interactive elements`() {
        val json = """
            {"url":"http://127.0.0.1:5173/","title":"Login","text":"Welcome back",
             "elements":[
               {"ref":"e1","role":"textbox","name":"Email","value":"","checked":null,"disabled":false},
               {"ref":"e2","role":"checkbox","name":"Remember me","value":"on","checked":true,"disabled":false}
             ]}
        """
        val page = parseSnapshot(wrap(json))
        assertEquals("http://127.0.0.1:5173/", page.url)
        assertEquals("Login", page.title)
        assertEquals("Welcome back", page.text)
        assertEquals(2, page.elements.size)
        assertEquals("Email", page.elements[0].name)
        assertEquals(true, page.elements[1].checked)
    }

    @Test
    fun `snapshot without elements is still valid`() {
        val page = parseSnapshot(wrap("""{"url":"http://x/","title":"","text":"","elements":[]}"""))
        assertTrue(page.elements.isEmpty())
    }

    @Test(expected = BrowserActionException::class)
    fun `null javascript result is reported`() {
        parseSnapshot(null)
    }

    @Test
    fun `action result carries message and failure`() {
        val ok = parseActionResult(wrap("""{"ok":true,"message":"Clicked e1."}"""))
        assertTrue(ok.ok)
        assertEquals("Clicked e1.", ok.message)

        val failed = parseActionResult(wrap("""{"ok":false,"error":"Unknown ref."}"""))
        assertTrue(!failed.ok)
        assertEquals("Unknown ref.", failed.error)
    }
}
