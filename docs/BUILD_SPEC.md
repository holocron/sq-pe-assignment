# Customer Activity Analytics — Authoritative Build Spec

**This document is the single source of truth for every implementation agent.**
Everything in the "Verified Environment Facts" section was empirically confirmed on this machine.
Do NOT guess API signatures — they changed in Spring Boot 4 / Spring AI 2.

> **Amended 2026-08-29 by stakeholder decision.** `risk_rules.threshold_logic` was originally
> specified here as a machine-parseable JSON rule DSL evaluated by a deterministic engine. That
> reading was overruled: **`threshold_logic` is natural language — effectively a prompt.** The ReAct
> agent reads the condition, fetches the customer's data with its tools, and judges whether the rule
> is triggered and what it contributes. The deterministic engine, the JSON grammar and the
> deterministic backfill are gone. Sections 3, 4, 6 and 7 below are the amended text; no table or
> column changed.

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
| Model router | **lemonade** (`lemond`) on holominix, ONE OpenAI-compatible endpoint: `http://localhost:13305/api/v1` (single SSH tunnel). It routes by model id and manages the per-model llama-server backends itself — do NOT tunnel per-model ports. |
| Chat model | model id `gpt-oss-120b-GGUF` (reasoning + native tool calling) |
| Embedding model | model id `Qwen3-Embedding-4B-GGUF`, **dimension = 2560** |
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
        .model("gpt-oss-120b-GGUF")
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
spring.ai.openai.api-key: none            # router needs no key, but the property must be present
# ONE base-url for chat and embeddings — the lemonade router dispatches on model id.
# Spring AI 2.x uses the official OpenAI Java SDK, which expects /v1 to be part of the base URL
# (base-url without /v1 returns 404 — this was hit and fixed during a spike).
spring.ai.openai.base-url: http://localhost:13305/api/v1
spring.ai.openai.chat.options.model: gpt-oss-120b-GGUF
spring.ai.openai.embedding.options.model: Qwen3-Embedding-4B-GGUF
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

`V5__readonly_role.sql` — the sandbox the agent's SQL executes in. **It changes none of the seven
assignment tables** — no columns, constraints, indexes or RLS. It adds:

* a login role `caa_readonly` (`NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION
  NOBYPASSRLS`, `CONNECTION LIMIT 8`) whose role-level settings are
  `default_transaction_read_only=on, statement_timeout=5s, lock_timeout=2s,
  idle_in_transaction_session_timeout=30s, search_path=caa_ro, work_mem=4MB,
  hash_mem_multiplier=1, temp_file_limit=64MB`;
* a schema `caa_ro` holding five `security_barrier` views over the five activity tables, each
  filtered by `caa_ro.current_scope()` — a transaction-local GUC the evaluator sets as a **bound
  parameter**. Unset, it is NULL and every view returns zero rows: the failure mode is "sees
  nothing", never "sees everyone";
* `REVOKE`s first, then exactly `CONNECT` on the database, `USAGE` on `caa_ro`, `SELECT` on the five
  views and `EXECUTE` on the scope function. Nothing on `public`, nothing on any base table, no
  `TEMPORARY`.

**Deliberate deviation:** the grant is on views, not on the base tables. `GRANT SELECT ON
public.transactions` would make `FROM public.transactions` a working cross-customer read for
anything that got past the validator — the one guarantee that must not depend on the validator.

**Superuser prerequisites** (done once by `scripts/db-setup.sh`): `ALTER ROLE caa CREATEROLE;`
required, and `GRANT SET ON PARAMETER temp_file_limit TO caa;` optional — without it the migration
raises a `WARNING` and carries on.

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
                 CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),  -- CANCELLED added by V6
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

## 3. Rule conditions (shared contract — the agent reads them, the editor writes them)

`risk_rules.threshold_logic` stores the rule condition **in natural language**. Nothing parses it.
The agent is shown the text verbatim and judges it against the customer's activity.

```text
A payment with payment.payment_method of SWIFT, whose amount is above 75,000 and whose
payment.receiver_bank_country is not CH.
Why it matters: SWIFT is the correspondent-banking rail, and above 75,000 a wire has left the
retail pattern entirely. Judge the set rather than the single transfer: several such wires to
different countries within one month is far stronger evidence than one wire to a country the
customer plainly trades with.
```

Contract for a condition:

* **Plain English.** A pasted `{"op":"AND",…}` document is **rejected** on write (400,
  `application/problem+json`, `field: "thresholdLogic"`), because stored verbatim it would be fed to
  the model as prose.
* **20–2,000 characters**, control characters stripped, CRLF unified, trimmed. Rule name unique and
  ≤ 160 characters; weight 0.01–999.99.
* It should state **a concrete threshold, a time window, the fields it depends on, and why the
  pattern matters** — the last part is what tells the agent how to score a marginal case.
* It reaches the model **fenced as data** (`PromptSafety.fence("rule_condition", …)`) and can neither
  close its own fence nor change the procedure. A rule's text can never excuse skipping a rule.
* The score the agent returns for a rule is its **estimate**, clamped to `[0, weight]`, `0.00` when
  not triggered.

### Field catalog — served by `GET /api/rules/field-catalog`

Reference material, not a grammar: it tells a rule author (and the reader of a condition) exactly
which data the agent can fetch, so a condition names fields that exist. The editor renders it beside
the condition box and inserts a field path on click.

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
| `list_risk_rules` | every applicable rule: `rule_id`, `rule_name`, `applies_to`, the condition (fenced as data), `weight`, how many transactions are in its scope, and whether it already has a verdict |
| `search_policy_knowledge` | **RAG** — vector similarity search over the knowledge base; returns chunks with source + section |
| `evaluate_rule` | judges ONE rule: `rule_id`, `sql`, `explanation`. The model supplies **no verdict and no score** — its SELECT is executed and the verdict is derived from the result. Refused, storing nothing: a blank explanation, a query the sandbox or PostgreSQL will not run, a returned id outside the rule's scope, and (on any attempt but the last) a query that uses none of a number the condition states |
| `submit_final_assessment` | terminal: `risk_level`, `summary`, `recommendations`, `escalation_justification` |

### Rule verdicts — MANDATORY: the model does not compute and does not compare

`threshold_logic` is prose. The agent translates it into one `SELECT`, PostgreSQL executes it, and
everything measurable is read off the result:

| | source |
|---|---|
| `triggered` | the query returned ≥ 1 row |
| `score_contribution` | the rule's `weight` when triggered, `0.00` when not |
| evidence | the `transaction_id`s the query returned |
| summary, recommendations, risk band | the agent |

Requirements:

1. **The query executes in a sandbox** (`com.sq.caa.sql`) — see §Security. Four rings: an allow-list
   validator; a wrapper that nests the fragment in CTEs filtered by a JDBC-bound customer id and
   inner-joins its output back to that customer's transactions; a login role whose entire privilege
   set is `SELECT` on five single-customer views; a read-only, always-rolled-back transaction under
   `statement_timeout`, `work_mem` and `temp_file_limit`.
2. **`ok=false` means UNJUDGED, never "not triggered".** A refused or erroring query records nothing
   and leaves the rule outstanding. Retries are bounded by `caa.agent.max-rule-sql-attempts`
   (default 3); the counter bounds *failures*, so a query that ran costs no budget.
3. **Threshold fidelity.** `ThresholdFidelity` compares the numbers in the condition with the numbers
   in the query and refuses one that uses none of a stated number, before it runs. It is bounded to
   two prompts per rule and **must not spend a query attempt** — a prompt never reaches the database,
   so charging it to the retry budget lets the check starve a rule of the attempts it needs to repair
   a genuinely invalid query, which is how a live run ended with an unjudged rule.
4. **Escalation.** The band is `RiskLevel.forScore(Σ scores)`. The agent may record a higher band
   with a justification; a lower one is refused; a rule whose query fired can never be cleared. Both
   bands and the justification are persisted in `analysis_runs.trace`.
5. Scores are **not** reproducible across runs — the query is authored fresh each time. This is
   accepted and must be stated in the UI, not hidden.

### Rule-coverage gate — MANDATORY, this is a graded requirement

The loop **must not be able to finish with an unevaluated rule.**

1. Before the loop, load all applicable rules (`applies_to = ALL` or matching an activity type the
   customer actually has). This is the **coverage set**.
2. Track `evaluated = {}` — filled by `evaluate_rule`, and only by a query that actually ran.
3. When the model stops calling tools (or calls `submit_final_assessment`) while
   `coverage_set - evaluated` is non-empty, **do not exit**. Append a `UserMessage` naming the exact
   missing `rule_id` / `rule_name` values and continue the loop. Log this as a `coverage_reprompt` step.
4. Repeat up to `MAX_STEPS` (default 40, configurable) and `max-coverage-reprompts` (default 3).
5. **There is no backfill.** Nothing behind the agent can close a gap. If the loop runs out of steps
   with rules still unjudged, the run is persisted **`FAILED`**, keeping the verdicts already
   obtained, with `coverage_complete = false`, `rules_evaluated` = the verdicts actually reached, and
   `analysis_runs.error` naming the rules that were never judged. A `coverage_failed` trace step
   carries the same names. A partial review must never be reported as a complete one.
6. `analysis_runs.coverage_complete` is therefore **`true` on every `COMPLETED` run**. It is derived
   from the outcome set (`unjudged.isEmpty() && outcomes >= rulesTotal`), not asserted by a caller,
   and the service restates the check before the single `COMPLETED` persist.
7. A row is written to `risk_assessments` for **every (in-scope transaction, rule)** pair — triggered
   rules with their distributed score, non-triggered ones with `score_contribution = 0.00`. "In
   scope" is decided by `applies_to` alone. A rule with **no** verdict writes **no** rows: a `0.00`
   row would be indistinguishable from "checked and cleared".

The false-negative defence is the gate plus the SQL path, not a second engine: the agent cannot skip
a rule, cannot record a finding it cannot cite, and cannot decide a threshold comparison at all.

### Prompting posture
The system prompt states the bank context and that this is **asymmetric-cost** work: a missed real risk
(false negative) is far more costly than an extra review (false positive). Instruct: when evidence is
ambiguous, escalate rather than clear; always cite policy via `search_policy_knowledge`; never assert a
number that did not come from a tool.

### Risk level banding (from total score)
`LOW < 25 ≤ MEDIUM < 50 ≤ HIGH < 75 ≤ CRITICAL`. Persist the numeric score too.

### Trace format (`analysis_runs.trace` JSONB)
```json
{"steps":[{"n":1,"type":"tool_call","tool":"list_risk_rules","args":{},"result_preview":"...","ms":812,
           "outcome":"12 rules in scope"},
          {"n":2,"type":"assistant","text":"..."},
          {"n":3,"type":"tool_call","tool":"evaluate_rule","args":{"rule_id":"..."},
           "result_preview":"...","ms":1421,
           "subject":"Structuring - repeated payments just below the reporting threshold",
           "outcome":"triggered +30.00 (rule 3 of 12)",
           "detail":{"sql":"WITH customer AS MATERIALIZED (...) SELECT ..."}},
          {"n":4,"type":"coverage_reprompt","missing":["<rule_id>"]},
          {"n":5,"type":"coverage_failed","missing":["<rule_id>"],
           "detail":{"rules_total":12,"unjudged_rule_names":["..."]}},
          {"n":6,"type":"final","risk_level":"CRITICAL",
           "detail":{"mechanical_risk_level":"HIGH","escalated":true,
                     "escalation_justification":"..."}}]}
```
The UI renders this, so keep it stable. `subject` and `outcome` are **optional** one-line labels
written where the meaning was known — the rule name and the verdict for `evaluate_rule`, the
transaction for `get_transaction_details`, and so on. They are omitted when empty, so a step written
before they existed is byte-identical to a note-less step today. Without them twelve rule verdicts
render as twelve identical rows, which is the whole reason they exist.

---

## 5. REST API contract (backend implements exactly; frontend consumes exactly)

Base path `/api`. JWT bearer auth. `401` unauthenticated, `403` wrong role.

```
POST   /api/auth/login            {username,password} -> {token, expiresAt, user:{username,fullName,role}}
GET    /api/auth/me               -> {username, fullName, role}

GET    /api/customers?query=&page=&size=      -> Page<CustomerSummary>  (query matches UUID or name)
GET    /api/customers/{customerId}            -> Customer
GET    /api/customers/{customerId}/summary    -> activity aggregates for the dashboard
GET    /api/customers/{customerId}/activity?type=&status=&from=&to=&minAmount=&maxAmount=&page=&size=&sort=
                                              -> Page<Transaction> (detail object inlined per type);
                                              minAmount/maxAmount = inclusive non-negative decimal
                                              bounds on amount (400 problem+json when non-numeric,
                                              negative or inverted); sort=<field>,<asc|desc>, field in
                                              {amount, createdAt, status, activityType};
                                              default createdAt,desc
GET    /api/transactions/{transactionId}      -> Transaction (full detail)

POST   /api/customers/{customerId}/analyses   -> 202 {assessmentId, status:"RUNNING"}
GET    /api/analyses/{assessmentId}           -> AnalysisResult (incl. trace + ruleEvaluations[])
GET    /api/analyses/{assessmentId}/stream    -> SSE live steps while RUNNING
GET    /api/customers/{customerId}/analyses   -> AnalysisSummary[] (history, newest first;
                                              sort=<field>,<asc|desc>, field in {createdAt|startedAt,
                                              totalScore, riskLevel})
POST   /api/analyses/{assessmentId}/cancel    -> 202 {assessmentId, status:"RUNNING"}: stop requested,
                                              the run reaches CANCELLED; 409 when already terminal

GET    /api/rules                             -> RiskRule[]           (ADMIN + OPERATOR read; each
                                              rule carries nullable lastFiredAt (latest risk_assessments
                                              row with score_contribution > 0) and lastJudgedAt (latest
                                              row whatever the score), null when never assessed)
POST   /api/rules                             -> RiskRule             (ADMIN)
PUT    /api/rules/{ruleId}                    -> RiskRule             (ADMIN)
DELETE /api/rules/{ruleId}                    -> 204                  (ADMIN; 409 problem+json when
                                              recorded risk_assessments rows still reference the rule -
                                              historical evidence is never cascade-deleted)
GET    /api/rules/field-catalog               -> FieldCatalogEntry[]  (ADMIN)
POST   /api/rules/test  {ruleName, thresholdLogic, appliesTo, weight, customerId}
                          -> {triggered, score, weight, rationale, matchedTransactions[],
                              matchedCount, evaluatedTransactionCount, customerName, model,
                              durationMs, notes[]}                                      (ADMIN)
                          -> 504 TIMEOUT / 503 BUSY|UNAVAILABLE / 502 MODEL_ERROR|UNREADABLE_ANSWER,
                             each problem+json with a `reason`. One model call, minutes long.

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
* **~10 risk rules** covering CARD / PAYMENT / CRYPTO / ALL, each with a real natural-language
  `threshold_logic` in the shape of section 3 — a concrete threshold, a window, the fields it needs
  and why it matters — and sensible `weight` values.
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
* **Rule editor** is an authoring surface for prose: an auto-growing condition textarea with a live
  character counter and blocking validation (empty, < 20, > 2,000, pasted JSON), worked example
  conditions, an advisory checklist (names a threshold / states a window / refers to data the agent
  can fetch), a weight control that shows what the weight is worth against the whole catalogue, an
  `applies_to` selector, the field catalog as a searchable reference panel that inserts a field path
  at the caret, and a "Test rule" button hitting `/api/rules/test`. The test panel makes the wait
  legible (it is a minutes-long model call) and states that the verdict is a judgement, not a
  calculation.
* The per-rule results table on the analysis page must visibly show **coverage**: every applicable
  rule listed with its verdict, score and the agent's rationale, triggered ones highlighted, and the
  count `N / N rules judged` so a reviewer can see nothing was skipped. Every verdict carries
  `source = AGENT_JUDGED`; an incomplete coverage set is the reason a run is `FAILED`, and the table
  says so rather than presenting it as a footnote.
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


---

## 9. Frontend visual identity

This is an **internal Swissquote application**. Before doing any frontend styling work, read
`docs/DESIGN_SYSTEM.md` — it carries Swissquote's real brand tokens (extracted from their
production stylesheets), the typography stack, and the one hard rule separating brand colour from
risk colour. The UI must look like a Swissquote internal tool.
