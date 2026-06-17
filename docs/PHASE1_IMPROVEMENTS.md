# Phase 1 개선 가이드 (앱 내, 인프라 무증설)

> 목적: 인프라를 늘리지 않고 **앱 코드만으로** 부하 안정성·운영성을 끌어올린다.
> 대상: ① LLM 동시성 게이트+큐+429, ② 응답 캐시(TTL), ③ 테넌트/키 레이트리밋, ④ Actuator 메트릭/헬스.
> 패키지: `com.miniwatson`. 코드 스니펫은 그대로 써도 되고 환경에 맞게 조정. (Spring Boot 기준)
>
> **권장 적용 순서:** ① 게이트 → ④ 메트릭 → ② 캐시 → ③ 레이트리밋
> (먼저 폭주를 막고, 보이게 만들고, 부하를 줄이고, 공정하게 나눈다.)

---

## ① LLM 동시성 게이트 + 큐 + 429

**왜:** 진짜 병목은 LLM 생성. 동시 호출이 슬롯을 넘으면 큐가 무한정 쌓여 전체가 무너진다.
**핵심:** `Semaphore`로 동시 Ollama 호출 수를 N으로 제한하고, 대기 한도를 넘으면 **429(Too Many Requests)** 로 빠르게 거절(우아한 저하).

**적용 위치:** 모든 생성이 지나가는 단일 길목 = `OllamaService.generate(...)`.

```java
// service/LlmGate.java
package com.miniwatson.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** LLM 동시 호출 수 제한 + 대기 타임아웃. 초과 시 LlmBusyException. */
@Component
public class LlmGate {
    private final Semaphore permits;
    private final long waitMs;
    private final AtomicInteger waiting = new AtomicInteger();

    public LlmGate(@Value("${llm.max-concurrent:3}") int maxConcurrent,
                   @Value("${llm.queue-timeout-ms:8000}") long waitMs) {
        this.permits = new Semaphore(maxConcurrent, true); // fair
        this.waitMs = waitMs;
    }

    public <T> T withPermit(java.util.function.Supplier<T> task) {
        boolean acquired = false;
        waiting.incrementAndGet();
        try {
            acquired = permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            if (!acquired) throw new LlmBusyException("LLM 처리 한도 초과 — 잠시 후 재시도");
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmBusyException("요청 대기 중 인터럽트");
        } finally {
            waiting.decrementAndGet();
            if (acquired) permits.release();
        }
    }

    public int availablePermits() { return permits.availablePermits(); }
    public int waitingCount() { return waiting.get(); }
}
```

```java
// service/LlmBusyException.java
package com.miniwatson.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS) // 429
public class LlmBusyException extends RuntimeException {
    public LlmBusyException(String msg) { super(msg); }
}
```

**OllamaService에 연결** — `generate(...)` 본문을 게이트로 감싼다:

```java
// OllamaService 필드에 주입
private final LlmGate llmGate;            // 생성자 파라미터 추가

private String generate(String prompt, String model, List<String> images,
                        String userQuestion, String sources) {
    return llmGate.withPermit(() -> doGenerate(prompt, model, images, userQuestion, sources));
}
// 기존 generate 본문을 private String doGenerate(...) 로 이름만 변경
```

**설정 (`application.yaml`)**
```yaml
llm:
  max-concurrent: ${LLM_MAX_CONCURRENT:3}   # = 단일 노드 동시 슬롯 G (CAPACITY §9로 실측해 정함)
  queue-timeout-ms: ${LLM_QUEUE_TIMEOUT_MS:8000}
```
> `max-concurrent`는 `docs/CAPACITY_AND_SCALING.md §9`의 실측 G와 일치시킨다. 너무 크면 GPU가 스래싱, 너무 작으면 처리량 손해.

---

## ② 응답 캐시 (TTL)

**왜:** 같은 질문이 반복되면(특히 기관 FAQ성 질의) LLM을 또 호출할 이유가 없다. 캐시 히트는 비용 0.
**핵심:** 키 = `(namespace, 정규화 질문, model, rerank, hybrid, graph)`. **TTL + 최대 크기**로 메모리 보호.

**의존성(권장: Caffeine)** — `pom.xml`
```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
</dependency>
```

```java
// service/AnswerCache.java
package com.miniwatson.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.miniwatson.service.RagService.RagResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class AnswerCache {
    private final Cache<String, RagResult> cache;
    private final boolean enabled;

    public AnswerCache(@Value("${cache.answers.enabled:true}") boolean enabled,
                       @Value("${cache.answers.ttl-seconds:600}") long ttl,
                       @Value("${cache.answers.max-size:1000}") long maxSize) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttl))
                .maximumSize(maxSize)
                .recordStats()                 // ④ 메트릭에서 hit/miss 노출
                .build();
    }

    public static String key(String ns, String q, String model, String rerank,
                             Boolean hybrid, Boolean graph) {
        String norm = q == null ? "" : q.trim().toLowerCase().replaceAll("\\s+", " ");
        return ns + "|" + norm + "|" + model + "|" + rerank + "|" + hybrid + "|" + graph;
    }

    public RagResult getOrNull(String key) { return enabled ? cache.getIfPresent(key) : null; }
    public void put(String key, RagResult v) { if (enabled) cache.put(key, v); }
    public Cache<String, RagResult> raw() { return cache; }
}
```

**RagService.ask 앞단에 적용:**
```java
String ck = AnswerCache.key(ns, question, model, rerankOverride, hybridOverride, graphOverride);
RagResult cached = answerCache.getOrNull(ck);
if (cached != null) return cached;          // 캐시 히트 → LLM 0회
// ... 기존 검색·생성 로직 ...
RagResult result = new RagResult(answer, topArticles, logId);
answerCache.put(ck, result);
return result;
```

**설정**
```yaml
cache:
  answers:
    enabled: true
    ttl-seconds: 600       # 공시·규정은 자주 안 바뀌니 10분~수시간도 가능
    max-size: 1000
```
> ⚠️ **주의:** 답변에 민감정보가 포함될 수 있으니 캐시는 **인메모리만**(외부 캐시·디스크 금지), 테넌트 키를 캐시키에 반드시 포함(테넌트 간 누수 방지). 데이터 갱신 시 무효화 훅 고려.

---

## ③ 테넌트/키 레이트리밋

**왜:** 한 기관/사용자의 폭주가 전체를 굶기지 않도록. 공공·금융은 공정 분배가 특히 중요.
**핵심:** API 키(또는 테넌트)별 **토큰 버킷**. 한도 초과 시 429 + `Retry-After`.

**의존성(권장: Bucket4j)** — `pom.xml`
```xml
<dependency>
  <groupId>com.bucket4j</groupId>
  <artifactId>bucket4j-core</artifactId>
  <version>8.10.1</version>
</dependency>
```

```java
// security/RateLimitFilter.java
package com.miniwatson.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** API 키(없으면 IP)별 토큰 버킷. /api/rag/ask 등 비싼 경로에 적용. */
@Component
@Order(20) // 인증 필터 뒤
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity, refillPerMin;

    public RateLimitFilter(@Value("${ratelimit.capacity:60}") int capacity,
                           @Value("${ratelimit.refill-per-min:60}") int refillPerMin) {
        this.capacity = capacity; this.refillPerMin = refillPerMin;
    }

    private Bucket bucket(String key) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(capacity,
                        io.github.bucket4j.Refill.greedy(refillPerMin, Duration.ofMinutes(1))))
                .build());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws java.io.IOException, jakarta.servlet.ServletException {
        if (!req.getRequestURI().startsWith("/api/rag/ask")) { chain.doFilter(req, res); return; }
        String key = req.getHeader("X-API-Key");
        if (key == null || key.isBlank()) key = req.getRemoteAddr();
        if (bucket(key).tryConsume(1)) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.getWriter().write("{\"error\":\"rate limit exceeded\"}");
        }
    }
}
```

**설정**
```yaml
ratelimit:
  capacity: 60          # 버스트 허용량(키당)
  refill-per-min: 60    # 분당 충전량
```
> 키 헤더명은 기존 `ApiKeyAuthFilter`가 쓰는 헤더와 맞춘다. 분산 배포(Phase 2) 시엔 ConcurrentHashMap 대신 Redis 기반 버킷으로 교체.

---

## ④ Actuator 메트릭 / 헬스

**왜:** 게이트·캐시·429가 실제로 어떻게 도는지 **보이게** 해야 부하를 측정하고 노드 수를 역산할 수 있다.

**의존성** — `pom.xml`
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<!-- 프로메테우스 스크랩 시 -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
```

**설정**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
```

**커스텀 메트릭 등록** (게이트 잔여 슬롯·대기수, 캐시 히트율, 429 수):
```java
// observability/MetricsConfig.java
package com.miniwatson.observability;

import com.miniwatson.service.AnswerCache;
import com.miniwatson.service.LlmGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Configuration
public class MetricsConfig {
    private final MeterRegistry reg;
    private final LlmGate gate;
    private final AnswerCache cache;

    public MetricsConfig(MeterRegistry reg, LlmGate gate, AnswerCache cache) {
        this.reg = reg; this.gate = gate; this.cache = cache;
    }

    @PostConstruct
    void bind() {
        reg.gauge("llm.permits.available", gate, LlmGate::availablePermits);
        reg.gauge("llm.waiting", gate, LlmGate::waitingCount);
        reg.gauge("cache.answers.hitRate", cache, c -> c.raw().stats().hitRate());
        reg.gauge("cache.answers.size", cache, c -> c.raw().estimatedSize());
    }
}
```
- 요청 지연/처리량은 actuator의 기본 `http.server.requests` 타이머로 자동 수집(p95·count). 별도 코드 불필요.
- 429 발생량은 `http.server.requests{status=429}` 로 조회.

> 폐쇄망: 프로메테우스/그라파나도 내부망에 둔다. `/actuator`는 인증 뒤로 숨기고 health만 공개.

---

## 검증 방법

```bash
# 부하: 동시 c를 올리며 p95/429 관찰 (게이트가 무너짐을 막는지)
hey -n 300 -c 8 -m POST -T application/json \
  -d '{"question":"공매도가 이뤄지는 시장의 대표 주가지수는?","namespace":"kr-securities"}' \
  http://localhost:8080/api/rag/ask

# 메트릭 확인
curl -s localhost:8080/actuator/metrics/llm.permits.available
curl -s localhost:8080/actuator/metrics/cache.answers.hitRate
curl -s 'localhost:8080/actuator/metrics/http.server.requests?tag=status:429'
```
기대: 동시 c가 `llm.max-concurrent`를 넘으면 초과분은 빠르게 429(무한 큐 대기 없음), 반복 질의는 캐시 히트로 LLM 호출 0.

---

## 적용 체크리스트

- [ ] `LlmGate` + `LlmBusyException` 추가, `OllamaService.generate`를 게이트로 래핑
- [ ] `application.yaml`에 `llm.max-concurrent`(= 실측 G), `queue-timeout-ms`
- [ ] Caffeine 의존성 + `AnswerCache` + `RagService.ask` 캐시 분기(테넌트 키 포함)
- [ ] Bucket4j 의존성 + `RateLimitFilter`(키/IP), 헤더명 기존 인증과 일치
- [ ] actuator/prometheus 의존성 + `MetricsConfig`, 엔드포인트 노출 최소화
- [ ] `hey`로 부하 → `llm.max-concurrent` 값 보정(`CAPACITY §9`)

> 이 네 가지는 **단일 노드 안정성·운영성**까지다. 동시 50~100(공공·금융 ~1,000명)·HA·인증연동은 `CAPACITY_AND_SCALING.md`의 Phase 2~3 — 별도 설계 후 진행.
