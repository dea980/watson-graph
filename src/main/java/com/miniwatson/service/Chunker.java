package com.miniwatson.service;

import java.util.List;
public interface Chunker {
    List<String> chunk(String text, int maxSize);
}
