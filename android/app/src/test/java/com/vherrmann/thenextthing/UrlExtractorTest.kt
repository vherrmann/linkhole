package com.vherrmann.thenextthing

import com.vherrmann.thenextthing.data.UrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    @Test fun plainUrl() = assertEquals("https://a.example/x", UrlExtractor.firstUrl("https://a.example/x"))

    @Test fun titleThenUrl() =
        assertEquals("https://youtu.be/abc?t=5", UrlExtractor.firstUrl("Great video\nhttps://youtu.be/abc?t=5"))

    @Test fun firstOfSeveral() =
        assertEquals("http://a.example", UrlExtractor.firstUrl("http://a.example and https://b.example"))

    @Test fun trailingPunctuation() {
        assertEquals("https://a.example/x", UrlExtractor.firstUrl("see (https://a.example/x), ok"))
        assertEquals("https://a.example/x", UrlExtractor.firstUrl("Look: https://a.example/x."))
    }

    @Test fun balancedParensKept() =
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", UrlExtractor.firstUrl("https://en.wikipedia.org/wiki/Foo_(bar)"))

    @Test fun none() {
        assertNull(UrlExtractor.firstUrl("no link"))
        assertNull(UrlExtractor.firstUrl(null))
        assertNull(UrlExtractor.firstUrl("ftp://x/y"))
    }
}
