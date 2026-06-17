# H2 Console — Inspecting the Audit Log

The H2 console is a browser-based SQL client that ships with the H2 in-memory database used by MiniWatson's governance layer. Use it to inspect the audit log (`QUERY_LOG` table) without writing any code.

> **Dev / demo only.** Never enable in production. See 8.

---

## 1. What it is

- Web UI at `/h2-console`
- Connects to the same in-memory DB the Spring Boot app uses
- Read/write SQL access to every table the app's JPA entities create
- Resets to empty when the app restarts (in-memory profile)

---

## 2. Enable it

In `application.yaml` (or `application-dev.yaml`):

```yaml
spring:
 h2:
  console:
   enabled: true
   path: /h2-console    # default; change if you want
   settings:
    web-allow-others: false  # only localhost — keep this false
 datasource:
  url: jdbc:h2:mem:miniwatson
  driver-class-name: org.h2.Driver
  username: sa
  password: ''
 jpa:
  database-platform: org.hibernate.dialect.H2Dialect
  hibernate:
   ddl-auto: update     # creates tables on first run
```

Restart Spring Boot.

---

## 3. Access it

1. App running on port 8080 → open `http://localhost:8080/h2-console`
2. Login form appears:

| Field    | Value            |
|--------------|-----------------------------|
| Saved Settings | `Generic H2 (Embedded)`  |
| Driver Class | `org.h2.Driver`       |
| JDBC URL   | `jdbc:h2:mem:miniwatson`  |
| User Name  | `sa`            |
| Password   | (leave blank)        |

3. Click **Connect**.

---

## 4. UI walkthrough

After login you see three regions:

```
┌─────────────────────────────────────────────────────┐
│ Run | Run Selected | Auto complete | Clear     │ ← toolbar
├─────────────────────────────────────────────────────┤
│ ── SQL editor (type queries here) ──        │
│                            │
├─────────────────────────────────────────────────────┤
│ ── results area (rows appear here) ──       │
└─────────────────────────────────────────────────────┘

Left sidebar:
 ▸ QUERY_LOG        ← MiniWatson audit table
 ▸ INFORMATION_SCHEMA   ← H2 metadata (ignore)
```

### Toolbar buttons

| Button      | What it does                       |
|------------------|-----------------------------------------------------------|
| **Run**     | Executes everything in the SQL editor           |
| **Run Selected** | Executes only the highlighted text             |
| **Auto complete**| Toggles column/table name suggestions while typing     |
| **Clear**    | Empties the SQL editor                   |
| **Auto commit**| When checked, every statement is committed immediately   |
| **Max rows: 1000**| Result row cap — safety against accidentally huge SELECTs |

### Sidebar

Click any table name → it inserts `SELECT * FROM <table>` into the editor. Convenient.

---

## 5. Query cookbook

### 5.1 Schema discovery

```sql
-- All MiniWatson tables (excludes H2 internals)
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'PUBLIC';

-- Columns of QUERY_LOG
SHOW COLUMNS FROM QUERY_LOG;
```

### 5.2 Recent activity

```sql
SELECT id, question, model, took_ms, created_at
FROM query_log
ORDER BY created_at DESC
LIMIT 10;
```

### 5.3 Per-model usage

```sql
SELECT model,
    COUNT(*)      AS calls,
    AVG(took_ms)    AS avg_latency_ms,
    MAX(took_ms)    AS p100_latency_ms
FROM query_log
GROUP BY model
ORDER BY calls DESC;
```

### 5.4 Slowest 5 calls

```sql
SELECT id, question, took_ms, source_count
FROM query_log
ORDER BY took_ms DESC
LIMIT 5;
```

### 5.5 Search by keyword

```sql
SELECT id, question, answer_preview, created_at
FROM query_log
WHERE LOWER(question) LIKE '%rag%'
ORDER BY created_at DESC;
```

### 5.6 Aggregate snapshot

```sql
SELECT COUNT(*)       AS total_calls,
    AVG(took_ms)     AS avg_ms,
    MIN(created_at)   AS first_call,
    MAX(created_at)   AS last_call,
    COUNT(DISTINCT user_id) AS distinct_users
FROM query_log;
```

### 5.7 Hourly distribution

```sql
SELECT FORMATDATETIME(created_at, 'yyyy-MM-dd HH:00') AS hour,
    COUNT(*)                    AS calls
FROM query_log
GROUP BY hour
ORDER BY hour DESC;
```

### 5.8 Manual insert (smoke test)

```sql
INSERT INTO query_log
 (user_id, endpoint, question, answer_preview, source_count, model, took_ms, created_at)
VALUES
 ('smoke-test', '/api/rag/ask', 'manual probe', 'manual answer', 0, 'gemma4', 100, CURRENT_TIMESTAMP);
```

Then verify with `SELECT * FROM query_log WHERE user_id = 'smoke-test';`

### 5.9 Reset table (without restarting app)

```sql
DELETE FROM query_log;
```

---

## 6. Common annoyances

| Symptom                | Fix                             |
|----------------------------------------|-------------------------------------------------------------|
| Autocomplete dropdown covers the page | Press **Esc**, or toolbar → `Auto complete = Off`      |
| "Database is already in use" on login | Another JVM is connected to the same H2 file — restart app |
| Table empty after restart       | In-memory profile — data is lost by design. Use `h2:file:` for persistence |
| Cannot find `QUERY_LOG`        | App hasn't logged a query yet (logging happens in `OllamaService.generate`) — run a `/api/rag/ask` first |
| 404 at `/h2-console`          | `spring.h2.console.enabled` not `true`, or wrong profile   |

---

## 7. Switching to file-backed H2 (survives restart)

If you want the audit log to survive app restarts but stay on the laptop, change the JDBC URL:

```yaml
spring:
 datasource:
  url: jdbc:h2:file:./data/h2/miniwatson;AUTO_SERVER=TRUE
```

`AUTO_SERVER=TRUE` lets the H2 console open the file while the app holds it.

> The dataset still lives in `./data/h2/` — same folder as your Parquet files. Both are local-only, no SaaS dependency.

---

## 8. Production note

H2 console is a security risk if exposed:
- It accepts arbitrary SQL
- Default `sa` user with empty password is well-known
- The H2 driver historically had RCE classes (CVE-2021-42392)

**For production** (`prod` profile):
- Set `spring.h2.console.enabled=false`
- Replace H2 entirely with PostgreSQL / Cloudant
- Audit access through the API only (`/api/governance/logs`)

This is the same posture watsonx.governance takes: governance data is queried through the platform's read APIs, not by handing out database credentials.

---

## 9. Why include it in MiniWatson

1. **Transparency.** Anyone reviewing the project can verify the audit log isn't simulated.
2. **Pedagogical.** Shows the JPA entity → SQL table mapping concretely.
3. **Demo-friendly.** A screenshot of `SELECT * FROM query_log` is the cleanest evidence of governance you can give in a slide deck.
4. **Honest about scope.** The 8 warning makes the dev/prod split explicit — a signal of judgment, not just feature-padding.

---

## 10. Suggested screenshot for the IBM follow-up

1. Open `/h2-console` → login (see 3).
2. Paste:
  ```sql
  SELECT id, question, model, source_count, took_ms, created_at
  FROM query_log
  ORDER BY created_at DESC
  LIMIT 5;
  ```
3. Click **Run**.
4. Screenshot the result rows.
5. Slide caption: *"Every RAG call is auditable. Inspectable from a browser. Schema mapped to watsonx.governance decision records (see GOVERNANCE.md 7)."*
