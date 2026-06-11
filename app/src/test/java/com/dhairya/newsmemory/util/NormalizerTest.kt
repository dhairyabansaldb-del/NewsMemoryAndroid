package com.dhairya.newsmemory.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerTest {

    @Test
    fun `lowercases and collapses whitespace`() {
        assertEquals("sensex falls 800 points", Normalizer.normalize("Sensex   Falls\t800  POINTS"))
    }

    @Test
    fun `strips punctuation and emoji`() {
        assertEquals(
            "breaking sensex falls 800 points",
            Normalizer.normalize("🚨 BREAKING: Sensex falls 800 points!!!")
        )
    }

    @Test
    fun `null and blank normalize to empty`() {
        assertEquals("", Normalizer.normalize(null))
        assertEquals("", Normalizer.normalize("   "))
    }

    @Test
    fun `same story different decoration hashes identically`() {
        val a = Normalizer.contentHash("🚨 Sensex falls 800 points!", "FII selling continues...")
        val b = Normalizer.contentHash("sensex falls 800 points", "fii selling continues")
        assertEquals(a, b)
    }

    @Test
    fun `different stories hash differently`() {
        val a = Normalizer.contentHash("Sensex falls 800 points", null)
        val b = Normalizer.contentHash("Sensex rises 800 points", null)
        assertNotEquals(a, b)
    }

    @Test
    fun `hash is hex sha256`() {
        val h = Normalizer.contentHash("title", "body")
        assertEquals(64, h.length)
        assertTrue(h.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `title tokens drop stopwords and short tokens`() {
        val tokens = Normalizer.titleTokens("The Sensex falls by 800 points in a day")
        assertEquals(setOf("sensex", "falls", "800", "points", "day"), tokens)
    }
}
