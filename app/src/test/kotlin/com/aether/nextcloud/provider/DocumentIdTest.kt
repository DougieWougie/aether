package com.aether.nextcloud.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdTest {
    @Test
    fun `round trips account and path`() {
        val id = DocumentId.parse("alice@cloud.example.com:/Documents/notes.md")
        assertEquals("alice@cloud.example.com", id.accountName)
        assertEquals("/Documents/notes.md", id.path)
        assertEquals("alice@cloud.example.com:/Documents/notes.md", id.toString())
    }

    @Test
    fun `root id`() {
        val root = DocumentId.root("alice@cloud.example.com")
        assertEquals("alice@cloud.example.com:/", root.toString())
        assertTrue(root.isRoot)
        assertEquals("", root.name)
        assertNull(root.parent)
        assertEquals(root, DocumentId.parse("alice@cloud.example.com:/"))
    }

    @Test
    fun `account names with a port split on the first colon-slash`() {
        val id = DocumentId.parse("alice@host:8443:/a/b")
        assertEquals("alice@host:8443", id.accountName)
        assertEquals("/a/b", id.path)
    }

    @Test
    fun `paths containing colons survive`() {
        val id = DocumentId.parse("alice@h:/Photos/2026-10-20 12:30:00.jpg")
        assertEquals("/Photos/2026-10-20 12:30:00.jpg", id.path)
    }

    @Test
    fun `normalizes duplicate and trailing slashes`() {
        assertEquals("/a/b", DocumentId.parse("x@h://a//b/").path)
        assertEquals("/a", DocumentId.parse("x@h:/./a/.").path)
    }

    @Test
    fun `rejects traversal and malformed ids`() {
        listOf("x@h:/a/../b", "nocolon", ":/path", "x@h", "").forEach {
            assertNull("should reject '$it'", DocumentId.parseOrNull(it))
        }
        assertNull(DocumentId.parseOrNull(null))
    }

    @Test
    fun `parent and child navigation`() {
        val file = DocumentId.parse("x@h:/Docs/sub/f.txt")
        assertEquals("f.txt", file.name)
        assertEquals(DocumentId.parse("x@h:/Docs/sub"), file.parent)
        assertEquals(DocumentId.root("x@h"), DocumentId.parse("x@h:/Docs").parent)
        assertEquals(DocumentId.parse("x@h:/a"), DocumentId.root("x@h").child("a"))
        assertEquals(DocumentId.parse("x@h:/a/b"), DocumentId.parse("x@h:/a").child("b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `child names cannot contain slashes`() {
        DocumentId.root("x@h").child("a/b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `child name cannot be dot dot`() {
        DocumentId.root("x@h").child("..")
    }

    @Test
    fun `ancestry`() {
        val root = DocumentId.root("x@h")
        val docs = DocumentId.parse("x@h:/Docs")
        val file = DocumentId.parse("x@h:/Docs/a.txt")
        assertTrue(root.isAncestorOf(docs))
        assertTrue(docs.isAncestorOf(file))
        assertTrue(root.isAncestorOf(file))
        assertFalse(docs.isAncestorOf(docs))
        assertFalse(file.isAncestorOf(docs))
        assertFalse("sibling with common prefix", DocumentId.parse("x@h:/Doc").isAncestorOf(file))
        assertFalse("other account", DocumentId.root("y@h").isAncestorOf(file))
    }
}
