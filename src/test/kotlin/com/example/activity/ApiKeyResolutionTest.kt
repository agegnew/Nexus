package com.example.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyResolutionTest {

    private fun resolve(stored: String? = null, environment: String? = null, file: String? = null) =
        ActivitySettings.resolveKey(stored, environment, file)

    @Test
    fun `the password safe wins over the fallbacks`() {
        assertEquals("sk-stored", resolve(stored = "sk-stored", environment = "sk-env", file = "sk-file"))
    }

    @Test
    fun `the environment is used when nothing is stored`() {
        assertEquals("sk-env", resolve(environment = "sk-env", file = "sk-file"))
    }

    @Test
    fun `the key file is the last resort`() {
        assertEquals("sk-file", resolve(file = "sk-file"))
    }

    @Test
    fun `blank values are skipped rather than winning`() {
        assertEquals("sk-file", resolve(stored = "", environment = "   ", file = "sk-file"))
        assertEquals("", resolve(stored = "", environment = null, file = "  \n "))
    }

    @Test
    fun `a key file written by an editor still works`() {
        // Trailing newline, a dotenv-style prefix and quotes are all easy to add by accident.
        assertEquals("sk-file", resolve(file = "sk-file\n"))
        assertEquals("sk-file", resolve(file = "OPENAI_API_KEY=sk-file"))
        assertEquals("sk-file", resolve(file = "\"sk-file\"\n"))
        assertEquals("sk-file", resolve(file = "OPENAI_API_KEY=\"sk-file\"\n"))
    }

    @Test
    fun `the key file on this machine is readable and looks like a key`() {
        val file = ActivitySettings.keyFile()
        assertTrue("No key file; run the setup step", !file.isNullOrBlank())

        val key = resolve(file = file)
        assertTrue("Key does not look like an OpenAI key", key.startsWith("sk-"))
        assertTrue("Key has whitespace in it", key.none { it.isWhitespace() })
    }
}
