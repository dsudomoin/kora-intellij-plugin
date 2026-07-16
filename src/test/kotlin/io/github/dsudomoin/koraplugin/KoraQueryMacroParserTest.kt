package io.github.dsudomoin.koraplugin

import io.github.dsudomoin.koraplugin.query.KoraMacroCommand
import io.github.dsudomoin.koraplugin.query.KoraMacroFilterMode
import io.github.dsudomoin.koraplugin.query.KoraQueryMacroParser
import org.junit.Assert.assertEquals
import org.junit.Test

class KoraQueryMacroParserTest {

    @Test
    fun `parses simple selects macro`() {
        val text = "SELECT %{return#selects} FROM t"
        val macros = KoraQueryMacroParser.parseMacros(text, 0)
        assertEquals(1, macros.size)
        val m = macros[0]
        assertEquals("return", m.target)
        assertEquals(KoraMacroCommand.SELECTS, m.command)
        assertEquals(KoraMacroFilterMode.NONE, m.filterMode)
        assertEquals("%{return#selects}", m.rawText)
        assertEquals(7, m.rangeInHost.startOffset)
        assertEquals(7 + "%{return#selects}".length, m.rangeInHost.endOffset)
    }

    @Test
    fun `parses where with id include filter`() {
        val macros = KoraQueryMacroParser.parseMacros("WHERE %{entity#where = @id}", 0)
        val m = macros.single()
        assertEquals("entity", m.target)
        assertEquals(KoraMacroCommand.WHERE, m.command)
        assertEquals(KoraMacroFilterMode.INCLUDE, m.filterMode)
        assertEquals(listOf("@id"), m.fields)
    }

    @Test
    fun `parses inserts with exclude id filter`() {
        val m = KoraQueryMacroParser.parseMacros("INSERT INTO %{entity#inserts-= @id}", 0).single()
        assertEquals(KoraMacroCommand.INSERTS, m.command)
        assertEquals(KoraMacroFilterMode.EXCLUDE, m.filterMode)
        assertEquals(listOf("@id"), m.fields)
    }

    @Test
    fun `parses inserts with explicit include fields`() {
        val m = KoraQueryMacroParser.parseMacros("%{entity#inserts = value1,value2}", 0).single()
        assertEquals(KoraMacroFilterMode.INCLUDE, m.filterMode)
        assertEquals(listOf("value1", "value2"), m.fields)
    }

    @Test
    fun `offsetInHost shifts ranges`() {
        val m = KoraQueryMacroParser.parseMacros("%{return#table}", 5).single()
        assertEquals(5, m.rangeInHost.startOffset)
    }

    @Test
    fun `parses named params and skips those inside macros`() {
        val params = KoraQueryMacroParser.parseParams("SELECT %{return#selects} FROM t WHERE id = :id AND x = :e.f", 0)
        assertEquals(2, params.size)
    }

    @Test
    fun `does not match postgres cast double colon`() {
        val params = KoraQueryMacroParser.parseParams("SELECT x::text FROM t", 0)
        assertEquals(0, params.size)
    }
}
