package com.miniwatson.controller;

import com.miniwatson.data.Article;
import com.miniwatson.service.IngestionService;
import com.miniwatson.service.OcrService;
import com.miniwatson.service.OllamaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/multimodal")
public class MultimodalController {

    private final OllamaService ollamaService;
    private final IngestionService ingestionService;
    private final OcrService ocrService;
    @Value("${ollama.vision-model}")
    private String visionModel;

    public MultimodalController(OllamaService ollamaService, IngestionService ingestionService, OcrService ocrService){
        this.ollamaService = ollamaService;
        this.ingestionService = ingestionService;
        this.ocrService = ocrService;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(
            @RequestParam("image") MultipartFile image,
            @RequestParam("question") String question,
            @RequestParam(value ="model", required = false) String model
    ) throws IOException {
        byte[] bytes = image.getBytes();
        String base64 = Base64.getEncoder().encodeToString(image.getBytes());

        String useModel = (model == null || model.isBlank()) ? visionModel : model;
        // OCR 로 정확한 텍스트 추출 -> 프롬트 주입 (grounding)
        String ocr = ocrService.extract(bytes);
        String prompt = (ocr.isBlank() ? "" :
                "Text extracted from the image (use it for exact numbers/text):\n" + ocr + "\n\n") + "Question: " + question;
        String answer = ollamaService.askWithImages(question, useModel, List.of(base64));

        return Map.of(
                "answer", answer,
                "model", useModel
        );
    }

    /**
     * 멀티모달 RAG ingest: 이미지를 지식베이스에 넣는다.
     * 비전 모델이 이미지를 설명 → 임베딩 → Article로 저장 → 이후 텍스트 RAG로 검색 가능.
     * POST /api/multimodal/ingest  (multipart: image, namespace?, model?)
     */
    @PostMapping("/ingest")
    public Article ingest(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "namespace", required = false, defaultValue = "default") String namespace,
            @RequestParam(value = "model", required = false) String model
    ) throws IOException {
        String useModel = (model == null || model.isBlank()) ? visionModel : model;
        return ingestionService.ingestImage(image, namespace, useModel);
    }

}
