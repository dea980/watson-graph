package com.miniwatson.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecursiveChunkerTest {

    private final RecursiveChunker chunker = new RecursiveChunker();

    @Test
    void shortTextStaysOneChunk() {
        List<String> chunks = chunker.chunk("short text", 1000);
        assertEquals(1, chunks.size());
    }

    @Test
    void longTextSplitsIntoMultiple() {
        String para = "A".repeat(300) + "\n\n" + "B".repeat(300) + "\n\n" + "C".repeat(300);
        List<String> chunks = chunker.chunk(para, 350);
        assertTrue(chunks.size() > 1, "should split when over maxSize");
    }

    @Test
    void everyChunkWithinMaxSize() {
        String text = ("word ".repeat(500)).trim();
        List<String> chunks = chunker.chunk(text, 200);
        for (String c : chunks) {
            assertTrue(c.length() <= 200, "chunk exceeded maxSize: " + c.length());
        }
    }

    @Test
    void noEmptyChunks() {
        List<String> chunks = chunker.chunk("a\n\n\n\nb", 100);
        assertTrue(chunks.stream().noneMatch(String::isBlank));
    }
}