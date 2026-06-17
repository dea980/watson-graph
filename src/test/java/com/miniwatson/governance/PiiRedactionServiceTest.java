package com.miniwatson.governance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PiiRedactionServiceTest {

    private final PiiRedactionService svc = new PiiRedactionService();

    @Test
    void masksEmail() {
        var r = svc.redact("contact me at john@acme.com please");
        assertTrue(r.text().contains("[EMAIL]"));
        assertFalse(r.text().contains("john@acme.com"));
        assertEquals(1, r.count());
    }

    @Test
    void masksPhoneAndSsn() {
        var r = svc.redact("call 010-1234-5678, ssn 123-45-6789");
        assertTrue(r.text().contains("[PHONE]"));
        assertTrue(r.text().contains("[SSN]"));
        assertEquals(2, r.count());
    }

    @Test
    void noPiiLeavesTextUnchanged() {
        var r = svc.redact("just a normal sentence");
        assertEquals("just a normal sentence", r.text());
        assertEquals(0, r.count());
    }

    @Test
    void handlesNull() {
        var r = svc.redact(null);
        assertEquals(0, r.count());
    }
}