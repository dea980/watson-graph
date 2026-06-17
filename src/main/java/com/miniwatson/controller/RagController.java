package com.miniwatson.controller;

import com.miniwatson.dto.AskRequest;
import com.miniwatson.service.OllamaService;
import com.miniwatson.service.RagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final OllamaService ollamaService;

    public RagController(RagService ragService, OllamaService ollamaService) {
        this.ragService = ragService;
        this.ollamaService = ollamaService;
    }

    /** Ask a RAG question. Body may include optional namespace + model. */
    @PostMapping("/ask")
    public RagService.RagResult ask(@RequestBody AskRequest request) throws IOException {
        return ragService.ask(
                            request.getQuestion(),
                            request.getNamespace(),
                            request.getModel(),
                            request.getRerank(),  // EVAL-ONLY
                            request.getHybrid(),  // EVAL-ONLY
                            request.getGraph());  // EVAL-ONLY
    }

    /** Multi-LLM: list selectable chat models and the default. */
    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "default", ollamaService.defaultModel(),
                "available", ollamaService.availableModels()
        );
    }
}
