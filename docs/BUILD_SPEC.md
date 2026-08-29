# Customer Activity Analytics — Authoritative Build Spec

**This document is the single source of truth for every implementation agent.**
Everything in the "Verified Environment Facts" section was empirically confirmed on this machine.
Do NOT guess API signatures — they changed in Spring Boot 4 / Spring AI 2.

---

## 1. Verified Environment Facts (DO NOT DEVIATE)

| Item | Verified value |
|---|---|
| JDK | 21 at `/opt/homebrew/opt/openjdk@21` (export `JAVA_HOME`) |
| Maven | 3.9.16 (`mvn`) |
| Spring Boot | **4.1.1** (note: NOT `4.1.1.RELEASE` — that artifact does not exist) |
| Spring AI | **2.0.1** |
| PostgreSQL | 17.11 on `localhost:5432`, db `caa`, user `caa`, password `caa` |
| pgvector | 0.8.6, extension `vector` already created |
| Chat model | `gpt-oss-120b` via `http://localhost:8001` (OpenAI-compatible, SSH tunnel to holominix) |
| Embedding model | `Qwen3-Embedding-4B` via `http://localhost:8002`, **dimension = 2560** |
| Tool calling | Natively supported by the chat model — verified |

Boot 4 renamed starters: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-flyway`,
and per-starter test artifacts (`spring-boot-starter-webmvc-test`, etc.). The pom is already correct — do not rewrite it.

### Spring AI 2.0.1 — verified working ReAct pattern

`internalToolExecutionEnabled` **no longer exists** in 2.0.1. Tool execution moved to a ChatClient advisor.
Therefore calling `ChatModel.call(...)` directly returns tool calls **unexecuted** — which is exactly what we want.
This exact code was compiled and run successfully against the live model:

```java
// MUST be OpenAiChatOptions — OpenAiChatModel throws ClassCastException on the generic
// ToolCallingChatOptions.builder() result. This was hit and fixed during a spike.
var options = OpenAiChatOptions.builder()
        .toolCallbacks(List.of(ToolCallbacks.from(toolsBean)))   // org.springframework.ai.support.ToolCallbacks
        .model("gpt-oss-120b")
        .maxTokens(2048)
        .build();

List<Message> history = new ArrayList<>();
history.add(new UserMessage(task));

while (steps++ < MAX_STEPS) {
    Prompt prompt = new Prompt(history, options);
    ChatResponse resp = chatModel.call(prompt);          // does NOT auto-execute tools
    if (resp.hasToolCalls()) {
        ToolExecutionResult ter = toolCallingManager.executeToolCalls(prompt, resp);
        history = new ArrayList<>(ter.conversationHistory());   // includes tool results
    } else {
        finalText = resp.getResult().getOutput().getText();
        break;
    }
}
```

Inject `ChatModel` and `ToolCallingManager` — both are auto-configured beans.
Tools are plain beans with `@Tool(name=..., description=...)` and `@ToolParam(description=...)` on args.
Note: the model emits `reasoning_content`; always allow generous `maxTokens` (>= 2048) or content comes back empty.

Configuration (already the shape to use in `application.yml`):
```yaml
spring.ai.openai.api-key: none            # local server needs no key, but the property must be present
spring.ai.openai.chat.base-url: http://localhost:8001
spring.ai.openai.chat.options.model: gpt-oss-120b
spring.ai.openai.embedding.base-url: http://localhost:8002
spring.ai.openai.embedding.options.model: Qwen3-Embedding-4B
spring.ai.vectorstore.pgvector.dimensions: 2560
```

---

## 2. Database Schema

Flyway migrations in `backend/src/main/resources/db/migration/`.
**The seven assignment tables keep their exact column names and types.**

`V1__baseline.sql` — assignment tables:

```sql
CREATE TYPE activity_type AS ENUM ('CARD','PAYMENT','CRYPTO');
CREATE TYPE rule_scope    AS ENUM ('CARD','PAYMENT','CRYPTO','ALL');

customers(customer_id UUID PK, last_name VARCHAR, first_name VARCHAR, dob DATE, country CHAR(2))
transactions(transaction_id UUID PK, customer_id UUID FK->customers, activity_type activity_type,
             amount DECIMAL(18,2), currency VARCHAR(10), status VARCHAR, created_at TIMESTAMP)
card_activity(transaction_id UUID PK FK->transactions, card_pan VARCHAR, card_type VARCHAR,
              merchant_name VARCHAR, mcc_code VARCHAR(4), card_present BOOLEAN,
              authorization_code VARCHAR, decline_reason VARCHAR NULL)
payment_activity(transaction_id UUID PK FK->transactions, payment_method VARCHAR, sender_account VARCHAR,
                 receiver_account VARCHAR, receiver_bank_country CHAR(2))
crypto_activity(transaction_id UUID PK FK->transactions, blockchain VARCHAR, wallet_address_from VARCHAR,
                wallet_address_to VARCHAR, tx_hash VARCHAR, exchange_name VARCHAR)
risk_rules(rule_id UUID PK, rule_name VARCHAR, applies_to rule_scope, threshold_logic TEXT, weight DECIMAL(5,2))
risk_assessments(assessment_id UUID, transaction_id UUID FK->transactions, rule_id UUID FK->risk_rules,
                 triggered_at TIMESTAMP, score_contribution DECIMAL(5,2))
```

### Documented deviation — `risk_assessments` primary key

The assignment lists `assessment_id` as PK, but also requires that one analysis produces
"newly created lines ... with the **common** assessment_id, checking each rule". Those two are
mutually exclusive. Resolution, which must be documented in the README:

* **Columns are exactly as specified — nothing added, nothing renamed.**
* `assessment_id` is the *shared* identifier of one analysis run (as the requirement demands).
* PRIMARY KEY is the composite `(assessment_id, transaction_id, rule_id)`.

One row is written **per (transaction, rule) pair evaluated** — including rules that did NOT
trigger, which get `score_contribution = 0.00`. This makes full rule coverage auditable from the
table alone and is what makes "no rule was skipped" provable.

`V2__app_tables.sql` — supporting tables (needed for login / RAG / persisted AI results, which the
assignment requires but did not schema out):

```sql
app_users(user_id UUID PK, username VARCHAR UNIQUE, password_hash VARCHAR, full_name VARCHAR,
          role VARCHAR CHECK (role IN ('ADMIN','OPERATOR')), enabled BOOLEAN, created_at TIMESTAMP)

-- AI narrative + run status for one assessment_id (the row-per-rule detail lives in risk_assessments)
analysis_runs(assessment_id UUID PK, customer_id UUID FK->customers, status VARCHAR
                 CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
              risk_level VARCHAR CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')) NULL,
              total_score DECIMAL(10,2) NULL, summary TEXT NULL, recommendations TEXT NULL,
              rules_total INT, rules_evaluated INT, coverage_complete BOOLEAN,
              model VARCHAR, steps INT, duration_ms BIGINT,
              trace JSONB,            -- full ReAct transcript, see section 4
              error TEXT NULL, requested_by VARCHAR, created_at TIMESTAMP, completed_at TIMESTAMP NULL)

knowledge_documents(document_id UUID PK, filename VARCHAR, title VARCHAR, mime_type VARCHAR,
                    size_bytes BIGINT, chunk_count INT, status VARCHAR, uploaded_by VARCHAR,
                    uploaded_at TIMESTAMP, error TEXT NULL)

-- Spring AI PgVectorStore-compatible table. Column names are fixed by PgVectorStore — do not rename.
document_chunks(id UUID PK DEFAULT uuid_generate_v4(), content TEXT, metadata JSON, embedding VECTOR(2560))
CREATE INDEX ON document_chunks USING hnsw (embedding vector_cosine_ops);
```

`document_chunks.metadata` carries: `document_id`, `filename`, `title`, `section_title`, `chunk_index`.
Configure the store with `initialize-schema: false` (Flyway owns the table) and `table-name: document_chunks`.
The RAG agent MUST verify at runtime that `PgVectorSchemaValidator` accepts this table.

`V3__seed.sql` — seed data (see section 6).

---

## 3. Risk Rule DSL (shared contract — backend evaluator AND frontend visual editor)

`risk_rules.threshold_logic` stores **JSON** in this exact shape:

```json
{
  "op": "AND",
  "conditions": [
    { "field": "amount", "operator": "GT", "value": 10000 },
    { "op": "OR", "conditions": [
        { "field": "payment.receiver_bank_country", "operator": "IN", "value": ["IR","KP","SY","RU","AF"] },
        { "field": "customer.country", "operator": "NEQ", "value": "US" }
    ]}
  ]
}
```

* A **group** node has `op` (`AND` | `OR` | `NOT`) and `conditions[]`. Nesting is allowed.
* A **leaf** node has `field`, `operator`, `value`.
* Operators: `GT GTE LT LTE EQ NEQ IN NOT_IN CONTAINS NOT_CONTAINS BETWEEN IS_NULL NOT_NULL MATCHES`
  (`BETWEEN` takes a 2-element array; `IS_NULL`/`NOT_NULL` take no value; `MATCHES` is a regex).
* Unknown field or type mismatch ⇒ the leaf evaluates to **false** and the evaluation is flagged
  `"degraded": true` — never throw during scoring.

### Field catalog — served by `GET /api/rules/field-catalog` so the editor is data-driven

| field | type | notes |
|---|---|---|
| `amount` | number | |
| `currency` | string | |
| `status` | enum | Completed, Pending, Failed, Reversed |
| `activity_type` | enum | CARD, PAYMENT, CRYPTO |
| `created_at` | datetime | |
| `hour_of_day` | number | 0-23, derived |
| `customer.country` | string | ISO-2 |
| `customer.age` | number | derived from dob |
| `card.mcc_code` | string | |
| `card.card_type` | enum | Debit, Credit, Prepaid |
| `card.card_present` | boolean | |
| `card.merchant_name` | string | |
| `card.decline_reason` | string | nullable |
| `payment.payment_method` | enum | ACH, Wire, SWIFT, P2P |
| `payment.receiver_bank_country` | string | ISO-2 |
| `payment.sender_account` | string | |
| `payment.receiver_account` | string | |
| `crypto.blockchain` | enum | BTC, ETH, USDT, XMR, ... |
| `crypto.exchange_name` | string | nullable |
| `crypto.wallet_address_to` | string | |
| `agg.tx_count_24h` | number | customer-level, relative to the transaction |
| `agg.amount_sum_24h` | number | |
| `agg.failed_count_24h` | number | |
| `agg.distinct_countries_30d` | number | |
| `agg.crypto_ratio_30d` | number | 0..1 |
| `agg.max_amount_30d` | number | |

`weight` is the score added when the rule matches. Score is capped per rule at `weight`.

---

## 4. The ReAct Risk Agent — hard requirements

Package `com.sq.caa.agent`. Triggered by `POST /api/customers/{id}/analyses`, runs **asynchronously**
(the model is slow; the API returns `202` immediately with `assessment_id` and `status=RUNNING`).

### Tools (each needs a clear, operator-readable `description`)

| tool | purpose |
|---|---|
| `get_customer_profile` | name, dob, age, country of the customer |
| `get_customer_activity_summary` | counts/sums per activity type, currencies, countries, velocity, failed ratio |
| `list_transactions` | page of transactions, filterable by `activity_type`, `status`, `min_amount` |
| `get_transaction_details` | one transaction incl. its CARD/PAYMENT/CRYPTO specifics |
| `list_risk_rules` | every applicable rule: `rule_id`, `rule_name`, `applies_to`, `threshold_logic`, `weight` |
| `search_policy_knowledge` | **RAG** — vector similarity search over the knowledge base; returns chunks with source + section |
| `evaluate_rule_deterministically` | runs the DSL engine for one `rule_id` over the customer's transactions; returns exactly which transactions match. The agent is told to use this for every numeric/threshold rule rather than eyeballing |
| `submit_rule_evaluation` | records the verdict for ONE rule: `rule_id`, `transaction_ids[]`, `triggered`, `score`, `rationale` |
| `submit_final_assessment` | terminal: `risk_level`, `summary`, `recommendations` |

### Rule-coverage gate — MANDATORY, this is a graded requirement

The loop **must not be able to finish with an unevaluated rule.**

1. Before the loop, load all applicable rules (`applies_to = ALL` or matching an activity type the
   customer actually has). This is the **coverage set**.
2. Track `evaluated = {}` — filled by `submit_rule_evaluation`.
3. When the model stops calling tools (or calls `submit_final_assessment`) while
   `coverage_set - evaluated` is non-empty, **do not exit**. Append a `UserMessage` naming the exact
   missing `rule_id` / `rule_name` values and continue the loop. Log this as a `coverage_reprompt` step.
4. Repeat up to `MAX_STEPS` (default 40, configurable).
5. **Deterministic backfill:** any rule still unevaluated after the loop is evaluated by the DSL engine
   and persisted with `source = DETERMINISTIC_FALLBACK`. Coverage is therefore *always* 100%.
6. `analysis_runs.coverage_complete` records whether the agent itself covered everything
   (true) or the fallback was needed (false). `rules_total` / `rules_evaluated` are persisted.
7. A row is written to `risk_assessments` for **every** rule in the coverage set — triggered ones with
   their score, non-triggered ones with `score_contribution = 0.00`.

Every rule evaluation is also cross-checked: if the agent says "not triggered" but the deterministic
engine says "triggered", the **deterministic result wins** for scoring and the disagreement is recorded
in the trace. This is the false-negative safety net the assignment asks for.

### Prompting posture
The system prompt states the bank context and that this is **asymmetric-cost** work: a missed real risk
(false negative) is far more costly than an extra review (false positive). Instruct: when evidence is
ambiguous, escalate rather than clear; always cite policy via `search_policy_knowledge`; never assert a
number that did not come from a tool.

### Risk level banding (from total score)
`LOW < 25 ≤ MEDIUM < 50 ≤ HIGH < 75 ≤ CRITICAL`. Persist the numeric score too.

### Trace format (`analysis_runs.trace` JSONB)
```json
{"steps":[{"n":1,"type":"tool_call","tool":"list_risk_rules","args":{},"result_preview":"...","ms":812},
          {"n":2,"type":"assistant","text":"..."},
          {"n":3,"type":"coverage_reprompt","missing":["<rule_id>"]},
          {"n":4,"type":"final","risk_level":"HIGH"}]}
```
The UI renders this, so keep it stable.

---

## 5. REST API contract (backend implements exactly; frontend consumes exactly)

Base path `/api`. JWT bearer auth. `401` unauthenticated, `403` wrong role.

```
POST   /api/auth/login            {username,password} -> {token, expiresAt, user:{username,fullName,role}}
GET    /api/auth/me               -> {username, fullName, role}

GET    /api/customers?query=&page=&size=      -> Page<CustomerSummary>  (query matches UUID or name)
GET    /api/customers/{customerId}            -> Customer
GET    /api/customers/{customerId}/summary    -> activity aggregates for the dashboard
GET    /api/customers/{customerId}/activity?type=&status=&from=&to=&page=&size=
                                              -> Page<Transaction> (detail object inlined per type)
GET    /api/transactions/{transactionId}      -> Transaction (full detail)

POST   /api/customers/{customerId}/analyses   -> 202 {assessmentId, status:"RUNNING"}
GET    /api/analyses/{assessmentId}           -> AnalysisResult (incl. trace + ruleEvaluations[])
GET    /api/analyses/{assessmentId}/stream    -> SSE live steps while RUNNING
GET    /api/customers/{customerId}/analyses   -> AnalysisSummary[] (history, newest first)

GET    /api/rules                             -> RiskRule[]           (ADMIN + OPERATOR read)
POST   /api/rules                             -> RiskRule             (ADMIN)
PUT    /api/rules/{ruleId}                    -> RiskRule             (ADMIN)
DELETE /api/rules/{ruleId}                    -> 204                  (ADMIN)
GET    /api/rules/field-catalog               -> FieldCatalogEntry[]  (ADMIN)
POST   /api/rules/test  {thresholdLogic, appliesTo, customerId?}
                                              -> {matchedCount, sampleMatches[], degraded}  (ADMIN)

GET    /api/knowledge/documents               -> KnowledgeDocument[]
POST   /api/knowledge/documents  (multipart "file") -> KnowledgeDocument   (ADMIN, .docx/.pdf only)
DELETE /api/knowledge/documents/{documentId}  -> 204                  (ADMIN)
POST   /api/knowledge/search {query, topK}    -> KnowledgeChunk[]     (ADMIN + OPERATOR)

GET    /api/users                             -> AppUser[]            (ADMIN)
```

Errors use RFC-7807 `application/problem+json` with `status`, `title`, `detail`.
All timestamps are ISO-8601 UTC strings. All ids are UUID strings.
JSON is camelCase (`customerId`, `riskLevel`, `scoreContribution`, `assessmentId`).

---

## 6. Seed data (`V3__seed.sql` + a seeding component)

* **Users** (BCrypt hashes, documented in the README):
  `admin/admin123` (ADMIN), `operator1/operator123`, `operator2/operator123`, `operator3/operator123` (OPERATOR).
* **~12 customers** across several countries, with recognisable names.
* **~400 transactions** spread over the last 90 days across all three activity types, each with its
  matching detail row. The data must contain **planted, findable risk patterns**, because the demo is
  worthless if every customer is clean:
  * one customer with structuring behaviour (many payments just under 10,000),
  * one with high-value SWIFT wires to a sanctioned-country bank,
  * one with heavy crypto exposure to a mixer/privacy chain and no exchange name,
  * one with a burst of card declines then a large card-not-present success,
  * several ordinary, low-risk customers so LOW/MEDIUM outcomes are reachable.
* **~10 risk rules** covering CARD / PAYMENT / CRYPTO / ALL, each with real `threshold_logic` JSON
  in the DSL of section 3 and sensible `weight` values.
* **2-3 knowledge-base policy documents** (generate real .docx/.pdf under `docs/sample-knowledge/`)
  covering AML thresholds, sanctioned jurisdictions, and crypto risk policy, so RAG returns something.

---

## 7. Frontend structure (`frontend/`, React 19 + TS + Vite 8 + Tailwind 4 + React Router 7 + TanStack Query 5)

```
src/
  api/            axios client (JWT interceptor, 401 -> logout), typed api modules, types.ts
  auth/           AuthContext, useAuth, ProtectedRoute, RoleGate
  components/     ui primitives (Button, Card, Table, Badge, Modal, Spinner, EmptyState), AppShell, Nav
  pages/
    LoginPage
    DashboardPage           customer search + recent analyses
    CustomerPage            profile, aggregates, activity tabs (Card/Payment/Crypto), "Run AI analysis"
    AnalysisPage            live ReAct trace, risk level, summary, recommendations, per-rule table w/ coverage
    AnalysisHistoryPage
    admin/RulesPage         visual rule editor (see below)
    admin/KnowledgePage     upload .docx/.pdf, list, delete
    admin/UsersPage
    KnowledgeSearchPage     RAG search for operators
```

* `vite.config.ts` proxies `/api` to `http://localhost:8080`.
* Tailwind 4 is configured via the `@tailwindcss/vite` plugin + `@import "tailwindcss";` in `index.css`
  (there is no `tailwind.config.js` init step in v4).
* **Rule editor** is a real visual builder: nested AND/OR/NOT groups, add/remove condition rows,
  field dropdown driven by `/api/rules/field-catalog`, operator list filtered by the field's type,
  value input typed to the field (number / text / multi-select for `IN` / date), a weight slider,
  an `applies_to` selector, a live JSON preview pane, and a "Test rule" button hitting `/api/rules/test`.
* The per-rule results table on the analysis page must visibly show **coverage**:
  every applicable rule listed, triggered ones highlighted, and the source of each verdict
  (`AGENT` vs `DETERMINISTIC_FALLBACK`) so a reviewer can see nothing was skipped.
* Role-aware nav: admin-only sections hidden for operators and enforced server-side too.

---

## 8. Conventions

* Java package root `com.sq.caa`; sub-packages `config security domain repository service agent rag rules web web.dto`.
* Constructor injection only. No field `@Autowired`. Lombok is available.
* DTOs are Java `record`s. Entities are JPA classes; never expose entities from controllers.
* Use `BigDecimal` for money, `UUID` for ids, `Instant` for timestamps.
* Validation with `jakarta.validation` annotations on request bodies.
* Tests: JUnit 5. Anything needing the live model must be tagged `@Tag("live")` and excluded by default.
* `git` is initialised on `main` with remote `origin` → `git@github.com:holocron/sq-pe-assignment.git`.
