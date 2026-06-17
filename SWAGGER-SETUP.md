# Swagger / OpenAPI Setup

Add live, interactive API docs at `http://localhost:8080/swagger-ui.html`.

---

## 1. Add Dependency to `pom.xml`

Inside `<dependencies>...</dependencies>`:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

> Spring Boot 3.x / 4.x compatible. No further config required for a basic UI.

---

## 2. (Optional) Top-level Metadata Bean

Create `src/main/java/com/miniwatson/config/OpenAPIConfig.java`:

```java
package com.miniwatson.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI miniWatsonOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("MiniWatson API")
                .version("0.1.0")
                .description(
                    "A laptop-scale reference of IBM watsonx's three-pillar " +
                    "architecture (data · ai · governance). " +
                    "Local-first RAG with Wikipedia ingest, Parquet+SNAPPY storage, " +
                    "Ollama foundation models, and JPA-backed audit log."
                )
                .contact(new Contact()
                    .name("Daeyeop Kim")
                    .email("kdea989@gmail.com")
                    .url("https://github.com/dea980/miniwatson"))
                .license(new License()
                    .name("MIT")
                    .url("https://opensource.org/licenses/MIT")));
    }
}
```

---

## 3. (Optional) Annotate Controllers

Make the auto-generated UI more readable with `@Tag`, `@Operation`, and `@ApiResponse`:

```java
package com.miniwatson.controller;

import com.miniwatson.data.Article;
import com.miniwatson.data.ArticleParquetStore;
import com.miniwatson.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/data")
@Tag(name = "Data Ingestion", description = "Wikipedia → embedding → Parquet pipeline")
public class DataController {

    private final IngestionService ingestionService;
    private final ArticleParquetStore articleStore;

    public DataController(IngestionService ingestionService, ArticleParquetStore articleStore) {
        this.ingestionService = ingestionService;
        this.articleStore = articleStore;
    }

    @Operation(
        summary = "Ingest a single Wikipedia article",
        description = "Fetches the summary, generates a 768-dim embedding via Ollama, and appends to Parquet."
    )
    @ApiResponse(responseCode = "200", description = "Article ingested",
        content = @Content(schema = @Schema(implementation = Article.class)))
    @PostMapping("/ingest")
    public Article ingest(@RequestParam String title) throws IOException {
        return ingestionService.ingest(title);
    }

    @Operation(
        summary = "Ingest multiple articles in one call",
        description = "Partial failures are surfaced in the 'errors' array; the call as a whole still returns 200."
    )
    @PostMapping("/ingest-batch")
    public Map<String, Object> ingestBatch(@RequestBody Map<String, List<String>> body) throws IOException {
        // ... existing implementation
        return Map.of();
    }

    @Operation(summary = "List all articles in the store")
    @GetMapping("/articles")
    public List<Article> getAllArticles() throws IOException {
        return articleStore.loadAll();
    }

    @Operation(summary = "Count of articles in the store")
    @GetMapping("/count")
    public Map<String, Integer> getCount() throws IOException {
        return Map.of("count", articleStore.loadAll().size());
    }
}
```

Repeat the same `@Tag` + `@Operation` pattern on `RagController`, `GovernanceController`, and `MultimodalController`.

Suggested tag names:

| Controller             | Tag                 | Description                                       |
|------------------------|---------------------|---------------------------------------------------|
| `DataController`       | "Data Ingestion"    | Ingest → chunk → embed → index                    |
| `RagController`        | "RAG"               | Retrieval-augmented generation                    |
| `GovernanceController` | "Governance"        | Audit logs, stats, feedback for every RAG call    |
| `MultimodalController` | "Multimodal"        | OCR + vision ingest/ask                           |

---

## 4. Hide `embedding` from Schema

`embedding` is 768 floats — not useful in Swagger UI.

```java
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(hidden = true)
@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
private List<Float> embedding;
```

The `@JsonProperty(WRITE_ONLY)` you already have hides it from JSON; `@Schema(hidden=true)` also hides it from the OpenAPI spec.

---

## 5. Default URLs

After restart, the following become available:

| URL                                               | Purpose                          |
|---------------------------------------------------|----------------------------------|
| `http://localhost:8080/swagger-ui.html`           | Interactive UI                   |
| `http://localhost:8080/v3/api-docs`               | Raw OpenAPI 3 JSON               |
| `http://localhost:8080/v3/api-docs.yaml`          | Raw OpenAPI 3 YAML               |

---

## 6. (Optional) Customize paths

In `application.yaml`:

```yaml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger
    operationsSorter: method
    tagsSorter: alpha
    displayRequestDuration: true
```

Now the UI lives at `http://localhost:8080/swagger` and the JSON at `/api-docs`.

---

## 7. Verify

```bash
./mvnw spring-boot:run

# Then in browser:
open http://localhost:8080/swagger-ui.html
```

You should see three sections — **Data Ingestion**, **RAG**, **Governance** — each with the endpoints, parameter editors, and "Try it out" buttons.

---

## 8. PPTX-friendly screenshot

For the IBM follow-up deck, take one screenshot of `swagger-ui.html` with the **RAG** tag expanded and the `/api/rag/ask` panel open. Crop tight. It reads as a one-liner: "API discoverable and testable from the browser, OpenAPI 3 compliant."

---

## 9. Why this matters (for IBM)

1. **Standards compliance** — OpenAPI 3 is the industry contract for HTTP APIs.
2. **Self-documenting** — engineering surface is discoverable without `grep`.
3. **DX maturity signal** — anyone (PM, PMO, IBM colleague) can hit the API in 30 seconds.
4. **Maps to watsonx.ai inference pattern** — request/response schema is the same shape as watsonx APIs.
