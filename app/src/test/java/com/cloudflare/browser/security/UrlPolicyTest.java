package com.cloudflare.browser.security;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UrlPolicyTest {
    @Test public void addsHttpsToHost() { assertEquals("https://example.com", UrlPolicy.parseWebUrl("example.com").toString()); }
    @Test public void acceptsHttpAndHttps() {
        assertEquals("http://example.com/path", UrlPolicy.parseWebUrl("http://example.com/path").toString());
        assertEquals("https://example.com", UrlPolicy.parseWebUrl("https://example.com").toString());
    }
    @Test public void rejectsUnsafeSchemesAndCredentials() {
        assertNull(UrlPolicy.parseWebUrl("javascript:alert(1)"));
        assertNull(UrlPolicy.parseWebUrl("file:///etc/passwd"));
        assertNull(UrlPolicy.parseWebUrl("https://user:pass@example.com"));
    }
    @Test public void rejectsControlAndOversizedInput() {
        assertNull(UrlPolicy.parseWebUrl("https://example.com\n.evil"));
        assertNull(UrlPolicy.parseWebUrl(new String(new char[8193]).replace('\0', 'a')));
    }
    @Test public void encodesSearchQuery() {
        assertTrue(UrlPolicy.searchUrl("kumo browser", "duckduckgo").contains("kumo%20browser"));
        assertTrue(UrlPolicy.searchUrl("hello", "google").startsWith("https://www.google.com/search?q="));
    }
}
