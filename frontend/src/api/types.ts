/**
 * Wire types for the Customer Activity Analytics REST API.
 *
 * These mirror BUILD_SPEC.md section 5 (REST contract), section 3 (risk rule
 * DSL) and section 4 (ReAct trace). JSON is camelCase, ids are UUID strings and
 * timestamps are ISO-8601 UTC strings unless noted otherwise.
 *
 * Anything that arrives in a shape the backend may serialise differently
 * (Spring `Page`, the trace JSONB, the per-type activity detail) has a `*Wire`
 * type here plus a normaliser in the matching api module, so feature code only
 * ever sees the canonical shape.
 */

/* -------------------------------------------------------------------------- */
/* Primitives                                                                  */
/* -------------------------------------------------------------------------- */

export type JsonPrimitive = string | number | boolean | null
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }

export type UUID = string
/** ISO-8601 UTC instant, e.g. `2026-08-29T11:04:07Z`. */
export type IsoDateTime = string
/** ISO-8601 calendar date, e.g. `1984-02-19`. */
export type IsoDate = string

/* -------------------------------------------------------------------------- */
/* Enumerations                                                                */
/* -------------------------------------------------------------------------- */

export const ROLES = ['ADMIN', 'OPERATOR'] as const
export type Role = (typeof ROLES)[number]

export const RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const
export type RiskLevel = (typeof RISK_LEVELS)[number]

export const ACTIVITY_TYPES = ['CARD', 'PAYMENT', 'CRYPTO'] as const
export type ActivityType = (typeof ACTIVITY_TYPES)[number]

export const RULE_SCOPES = ['CARD', 'PAYMENT', 'CRYPTO', 'ALL'] as const
export type RuleScope = (typeof RULE_SCOPES)[number]

/** Values seeded by the backend (see the `status` row of the field catalog). */
export const TRANSACTION_STATUSES = [
  'Completed',
  'Pending',
  'Failed',
  'Reversed',
] as const
export type TransactionStatus = (typeof TRANSACTION_STATUSES)[number]

export const ANALYSIS_STATUSES = ['RUNNING', 'COMPLETED', 'FAILED'] as const
export type AnalysisStatus = (typeof ANALYSIS_STATUSES)[number]

/** Where a rule verdict came from — rendered on the coverage table. */
export const EVALUATION_SOURCES = ['AGENT', 'DETERMINISTIC_FALLBACK'] as const
export type EvaluationSource = (typeof EVALUATION_SOURCES)[number]

export const KNOWLEDGE_DOCUMENT_STATUSES = [
  'PENDING',
  'PROCESSING',
  'INDEXED',
  'READY',
  'FAILED',
] as const
export type KnowledgeDocumentStatus = (typeof KNOWLEDGE_DOCUMENT_STATUSES)[number]

/* -------------------------------------------------------------------------- */
/* Auth — POST /api/auth/login, GET /api/auth/me                               */
/* -------------------------------------------------------------------------- */

export interface LoginRequest {
  username: string
  password: string
}

/** `GET /api/auth/me` and the `user` object inside the login response. */
export interface User {
  username: string
  fullName: string
  role: Role
}

export interface AuthResponse {
  token: string
  expiresAt: IsoDateTime
  user: User
}

/** `GET /api/users` — the admin user list (`app_users` minus the hash). */
export interface AppUser {
  userId: UUID
  username: string
  fullName: string
  role: Role
  enabled: boolean
  createdAt: IsoDateTime
}

/* -------------------------------------------------------------------------- */
/* Paging — Spring `Page<T>`                                                   */
/* -------------------------------------------------------------------------- */

/** Spring Boot 4 `PagedModel` metadata block. */
export interface SpringPageMetadata {
  size: number
  number: number
  totalElements: number
  totalPages: number
}

/**
 * Three serialisations are accepted on the wire, and `page` means a different
 * thing in two of them:
 *
 *  - **this backend** (`web/dto/PageResponse.java`) sends a flat envelope where
 *    `page` is the zero-based page *index*:
 *    `{content, page: 1, size: 5, totalElements: 12, totalPages: 3}` — verified
 *    live against `GET /api/customers?page=1&size=5`;
 *  - Boot 4 `PagedModel` nests the metadata under `page` as an *object*;
 *  - the classic flattened `Page` puts the index in `number`.
 *
 * `toPage` in `api/client` collapses all three; the numeric form is the one
 * that actually arrives.
 */
export interface SpringPage<T> {
  content: T[]
  /** Zero-based page index (this backend) or the `PagedModel` metadata object. */
  page?: number | SpringPageMetadata
  totalElements?: number
  totalPages?: number
  size?: number
  number?: number
  numberOfElements?: number
  first?: boolean
  last?: boolean
  empty?: boolean
}

/** Canonical page shape handed to components. `page` is 0-based. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
  empty: boolean
}

export interface PageParams {
  page?: number
  size?: number
}

/* -------------------------------------------------------------------------- */
/* Customers                                                                   */
/* -------------------------------------------------------------------------- */

/** `GET /api/customers/{customerId}` — `CustomerDtos.CustomerDetail`. */
export interface Customer {
  customerId: UUID
  firstName: string
  lastName: string
  /** Composed server-side; the pages fall back to `firstName lastName`. */
  fullName?: string | null
  dob: IsoDate | null
  country: string
  /** Derived server-side from `dob` (also exposed as `customer.age` in the DSL). */
  age?: number | null
  transactionCount?: number | null
  analysisCount?: number | null
}

/**
 * Row shape of `GET /api/customers` — `CustomerDtos.CustomerSummary`.
 *
 * `customerId, firstName, lastName, fullName, dob, age, country` are always
 * present. The aggregates below are served by the same DTO; every consumer
 * still treats them as optional and renders an em dash when they are absent, so
 * the table degrades rather than lying.
 *
 * `totalAmount` is **not** a cross-currency total — there are no FX rates in
 * this system. It is the sum for the customer's dominant currency, named by
 * `totalAmountCurrency`, with `mixedCurrency` set when other currencies exist
 * on file. Label it accordingly.
 */
export interface CustomerSummary {
  customerId: UUID
  firstName: string
  lastName: string
  fullName?: string | null
  dob?: IsoDate | null
  country: string
  age?: number | null
  transactionCount?: number | null
  totalAmount?: number | null
  totalAmountCurrency?: string | null
  mixedCurrency?: boolean | null
  lastActivityAt?: IsoDateTime | null
  /** Verdict of the most recent COMPLETED analysis; null when never analysed. */
  lastRiskLevel?: RiskLevel | null
  lastAnalysisAt?: IsoDateTime | null
}

export interface CustomerSearchParams extends PageParams {
  /** Matches a customer UUID or a name fragment. */
  query?: string
}

/**
 * One bucket of `ActivitySummary.byActivityType`
 * (`CustomerDtos.ActivityTypeBreakdown`). All three types are always present,
 * zero-filled when the customer has no activity of that kind.
 */
export interface ActivityTypeBreakdown {
  activityType: ActivityType
  transactionCount: number
  totalAmount: number
  minAmount?: number | null
  maxAmount?: number | null
  avgAmount?: number | null
  firstAt?: IsoDateTime | null
  lastAt?: IsoDateTime | null
}

/** One bucket of `ActivitySummary.byStatus` (`CustomerDtos.StatusBreakdown`). */
export interface StatusBreakdown {
  status: string
  transactionCount: number
  totalAmount: number
}

/** One bucket of `ActivitySummary.byCurrency`. */
export interface CurrencyBreakdown {
  currency: string
  transactionCount: number
  totalAmount: number
}

/** One bucket of `ActivitySummary.counterpartyCountries`. */
export interface CountryBreakdown {
  country: string
  transactionCount: number
  totalAmount: number
}

/** One day of `ActivitySummary.dailyTimeline`; zero-filled, oldest first. */
export interface DailyActivityPoint {
  date: IsoDate
  transactionCount: number
  totalAmount: number
}

/** The most recent run for this customer, inlined in the summary. */
export interface LatestAnalysisSummary {
  assessmentId: UUID
  status: AnalysisStatus
  riskLevel: RiskLevel | null
  totalScore: number | null
  coverageComplete?: boolean | null
  createdAt: IsoDateTime
  completedAt?: IsoDateTime | null
}

/**
 * `GET /api/customers/{customerId}/summary` — exactly what
 * `CustomerDtos.CustomerActivitySummary` serialises. Verified against the live
 * endpoint: the counts are `transactionCount` (never `count`), the currency and
 * country rollups are `byCurrency` / `counterpartyCountries` objects (never the
 * `currencies` / `countries` string arrays this type used to claim).
 */
export interface ActivitySummaryWire {
  customerId: UUID
  customer?: CustomerSummary | null
  totalTransactions: number
  totalAmount: number
  firstActivityAt?: IsoDateTime | null
  lastActivityAt?: IsoDateTime | null
  completedCount: number
  pendingCount: number
  failedCount: number
  reversedCount: number
  failedAmount: number
  reversedAmount: number
  failedRatio?: number | null
  distinctCurrencies: number
  distinctCounterpartyCountries: number
  /**
   * The same `agg.*` values the rule engine scores on, taken verbatim from the
   * customer's evaluation batch. Each is the PEAK of its rolling window over
   * the customer's whole history (the server folds every per-transaction
   * snapshot with `max`), which is the figure a threshold rule was measured
   * against. They are not readings for the last 24 hours or 30 days, and there
   * is no single instant they are "as of" — the UI must label them as peaks.
   */
  txCount24h: number
  amountSum24h: number
  failedCount24h: number
  distinctCountries30d: number
  cryptoRatio30d: number
  maxAmount30d: number
  byActivityType: ActivityTypeBreakdown[]
  byStatus: StatusBreakdown[]
  byCurrency: CurrencyBreakdown[]
  counterpartyCountries: CountryBreakdown[]
  dailyTimeline: DailyActivityPoint[]
  latestAnalysis?: LatestAnalysisSummary | null
}

/**
 * The wire payload plus the one value the UI needs that the API does not send
 * as such: the plain list of currency codes, derived from `byCurrency` by
 * `normalizeActivitySummary`. Money is only labelled with a currency symbol
 * when the customer used exactly one.
 */
export interface ActivitySummary extends ActivitySummaryWire {
  /** Derived client-side from `byCurrency[].currency` — not a wire field. */
  currencies: string[]
}

/* -------------------------------------------------------------------------- */
/* Transactions                                                                */
/* -------------------------------------------------------------------------- */

export interface CardActivity {
  transactionId: UUID
  cardPan: string
  cardType: string
  merchantName: string
  mccCode: string
  cardPresent: boolean
  authorizationCode: string | null
  declineReason: string | null
}

export interface PaymentActivity {
  transactionId: UUID
  paymentMethod: string
  senderAccount: string
  receiverAccount: string
  receiverBankCountry: string
}

export interface CryptoActivity {
  transactionId: UUID
  blockchain: string
  walletAddressFrom: string
  walletAddressTo: string
  txHash: string
  exchangeName: string | null
}

export type ActivityDetail = CardActivity | PaymentActivity | CryptoActivity

interface TransactionBase {
  transactionId: UUID
  customerId: UUID
  amount: number
  currency: string
  status: TransactionStatus
  createdAt: IsoDateTime
}

export interface CardTransaction extends TransactionBase {
  activityType: 'CARD'
  card: CardActivity | null
}

export interface PaymentTransaction extends TransactionBase {
  activityType: 'PAYMENT'
  payment: PaymentActivity | null
}

export interface CryptoTransaction extends TransactionBase {
  activityType: 'CRYPTO'
  crypto: CryptoActivity | null
}

/**
 * Discriminated on `activityType`; the matching detail object is inlined under
 * `card` / `payment` / `crypto`. Use `activityDetail(tx)` from `lib/activity`
 * when the specific key does not matter.
 */
export type Transaction = CardTransaction | PaymentTransaction | CryptoTransaction

/**
 * Tolerant wire shape: the backend may inline the detail under its named key
 * or under a generic `detail` key. `normalizeTransaction` collapses both.
 */
export interface TransactionWire extends TransactionBase {
  activityType: ActivityType
  card?: CardActivity | null
  payment?: PaymentActivity | null
  crypto?: CryptoActivity | null
  detail?: ActivityDetail | null
}

export interface ActivityQueryParams extends PageParams {
  type?: ActivityType | null
  status?: TransactionStatus | string | null
  /** ISO-8601 instant or date, inclusive lower bound. */
  from?: string | null
  /** ISO-8601 instant or date, inclusive upper bound. */
  to?: string | null
}

/* -------------------------------------------------------------------------- */
/* Risk rules and the threshold DSL (BUILD_SPEC section 3)                     */
/* -------------------------------------------------------------------------- */

export const RULE_GROUP_OPS = ['AND', 'OR', 'NOT'] as const
export type RuleGroupOp = (typeof RULE_GROUP_OPS)[number]

export const RULE_OPERATORS = [
  'GT',
  'GTE',
  'LT',
  'LTE',
  'EQ',
  'NEQ',
  'IN',
  'NOT_IN',
  'CONTAINS',
  'NOT_CONTAINS',
  'BETWEEN',
  'IS_NULL',
  'NOT_NULL',
  'MATCHES',
] as const
export type RuleOperator = (typeof RULE_OPERATORS)[number]

export type RuleScalar = string | number | boolean
/** `IN`/`NOT_IN` take a list, `BETWEEN` a 2-element list, `IS_NULL`/`NOT_NULL` nothing. */
export type RuleValue = RuleScalar | RuleScalar[] | null

/** Leaf node: `{ field, operator, value }`. */
export interface RuleCondition {
  field: string
  operator: RuleOperator
  value?: RuleValue
}

/** Group node: `{ op, conditions[] }`. Nesting is unbounded. */
export interface RuleGroup {
  op: RuleGroupOp
  conditions: RuleNode[]
}

/**
 * Recursive discriminated union backing the visual rule editor. Narrow with
 * `isRuleGroup(node)` / `isRuleCondition(node)` from `lib/rules`.
 */
export type RuleNode = RuleGroup | RuleCondition

export interface RiskRule {
  ruleId: UUID
  ruleName: string
  appliesTo: RuleScope
  thresholdLogic: RuleNode
  weight: number
}

/** `thresholdLogic` may arrive as a JSON string because the column is TEXT. */
export interface RiskRuleWire {
  ruleId: UUID
  ruleName: string
  appliesTo: RuleScope
  thresholdLogic: RuleNode | string | null
  weight: number | string
}

export interface RiskRuleInput {
  ruleName: string
  appliesTo: RuleScope
  thresholdLogic: RuleNode
  weight: number
}

export const FIELD_TYPES = [
  'number',
  'string',
  'enum',
  'boolean',
  'datetime',
  'date',
] as const
/** Editor-side field type. Lowercase — see `FieldCatalogEntryWire.type`. */
export type FieldType = (typeof FIELD_TYPES)[number]

/**
 * `GET /api/rules/field-catalog` exactly as the backend serialises it
 * (`RuleDtos.FieldCatalogEntry`). Verified live: 26 entries, `type` is the Java
 * enum name in UPPER CASE (`NUMBER | STRING | ENUM | BOOLEAN | DATETIME`), the
 * allowed enum members arrive under `options` (not `values`), the prose is
 * `description` (not `notes`), and every entry carries the authoritative list
 * of `operators` the backend will accept for that field.
 *
 * `normalizeFieldCatalogEntry` in `api/rules` maps this onto the editor shape.
 */
export interface FieldCatalogEntryWire {
  field: string
  label?: string | null
  type: string
  appliesTo?: RuleScope | null
  operators?: string[] | null
  options?: string[] | null
  optionsClosed?: boolean | null
  nullable?: boolean | null
  description?: string | null
}

/** Normalised catalog entry — what the visual rule editor consumes. */
export interface FieldCatalogEntry {
  field: string
  type: FieldType
  /** Human label; falls back to the field path when absent. */
  label?: string | null
  /** Prose description of the field (wire: `description`). */
  notes?: string | null
  /** Allowed values for `enum` fields (wire: `options`). */
  values?: string[] | null
  /** True when `values` is exhaustive, so free text must be rejected. */
  valuesClosed?: boolean | null
  /** The operators the backend accepts for this field — authoritative. */
  operators?: RuleOperator[] | null
  /** Activity scope the field is available on. */
  appliesTo?: RuleScope | null
  nullable?: boolean | null
  /** Optional grouping hint, e.g. `card`, `payment`, `agg`. */
  group?: string | null
}

export interface RuleTestRequest {
  thresholdLogic: RuleNode
  appliesTo: RuleScope
  customerId?: UUID | null
}

export interface RuleTestMatch {
  transactionId: UUID
  customerId?: UUID
  customerName?: string | null
  activityType?: ActivityType
  amount?: number
  currency?: string
  status?: string
  createdAt?: IsoDateTime
  /** Per-leaf trace of why the row matched, e.g. `(amount=12500 GT 10000 [true])`. */
  explanation?: string | null
}

/** `POST /api/rules/test` — `RuleDtos.RuleTestResponse`. */
export interface RuleTestResult {
  matchedCount: number
  sampleMatches: RuleTestMatch[]
  /** True when a leaf could not be evaluated (unknown field / type mismatch). */
  degraded: boolean
  evaluatedCount?: number | null
  customerCount?: number | null
  /**
   * The concrete degradation reasons the evaluator collected, e.g.
   * `"'card.decline_reason' has no value on at least one transaction"`.
   * Always present (possibly empty) whenever `degraded` is true.
   */
  notes: string[]
}

/* -------------------------------------------------------------------------- */
/* Analyses (BUILD_SPEC section 4)                                             */
/* -------------------------------------------------------------------------- */

/** `POST /api/customers/{customerId}/analyses` -> 202. */
export interface AnalysisRun {
  assessmentId: UUID
  status: AnalysisStatus
}

/** `GET /api/customers/{customerId}/analyses` — history rows, newest first. */
export interface AnalysisSummary {
  assessmentId: UUID
  customerId: UUID
  customerName?: string | null
  status: AnalysisStatus
  riskLevel: RiskLevel | null
  totalScore: number | null
  rulesTotal?: number | null
  rulesEvaluated?: number | null
  coverageComplete?: boolean | null
  model?: string | null
  steps?: number | null
  durationMs?: number | null
  requestedBy?: string | null
  createdAt: IsoDateTime
  completedAt?: IsoDateTime | null
  error?: string | null
}

/**
 * One row of `risk_assessments`, joined with its rule for display —
 * `AnalysisDtos.RuleEvaluationView`.
 *
 * Verified live against `GET /api/analyses/{assessmentId}`: the score is
 * `score` (never `scoreContribution`) and the evidence ids are
 * `matchedTransactionIds` (never `transactionIds`).
 */
export interface RuleEvaluation {
  ruleId: UUID
  ruleName: string
  appliesTo?: RuleScope | null
  weight?: number | null
  triggered: boolean
  /** `risk_assessments.score_contribution` — 0.00 when not triggered. */
  score: number
  source: EvaluationSource
  /** How many transactions the rule was run against. */
  evaluatedTransactionCount?: number | null
  matchedCount?: number | null
  /** The transactions that satisfied the rule. Empty, never absent. */
  matchedTransactionIds: UUID[]
  /** True when a leaf could not be evaluated; see `degradationNotes`. */
  degraded?: boolean | null
  degradationNotes?: string[] | null
  /** The DSL engine's own account of the verdict. */
  explanation?: string | null
  /** The agent's narrative justification. */
  rationale?: string | null
  agentTriggered?: boolean | null
  agentScore?: number | null
  /** True when the agent verdict differed from the deterministic engine. */
  disagreement?: boolean | null
}

export interface ToolCallTraceStep {
  type: 'tool_call'
  n: number
  tool: string
  args?: JsonValue
  resultPreview?: string | null
  ms?: number | null
}

export interface AssistantTraceStep {
  type: 'assistant'
  n: number
  text: string
  ms?: number | null
}

export interface CoverageRepromptTraceStep {
  type: 'coverage_reprompt'
  n: number
  missing: string[]
  ms?: number | null
}

export interface FinalTraceStep {
  type: 'final'
  n: number
  riskLevel: RiskLevel | null
  ms?: number | null
}

export interface ErrorTraceStep {
  type: 'error'
  n: number
  message: string
  ms?: number | null
}

/** Any step type the backend adds later still renders, never crashes. */
export interface UnknownTraceStep {
  type: 'unknown'
  n: number
  rawType: string
  raw: JsonObject
  ms?: number | null
}

export type TraceStep =
  | ToolCallTraceStep
  | AssistantTraceStep
  | CoverageRepromptTraceStep
  | FinalTraceStep
  | ErrorTraceStep
  | UnknownTraceStep

export interface AnalysisTrace {
  steps: TraceStep[]
}

/** Coverage counters the backend derives; rendered by the coverage panel. */
interface AnalysisCoverageCounters {
  /** Rules the agent itself submitted a verdict for. */
  rulesEvaluatedByAgent?: number | null
  /** Rules closed by the deterministic backfill. */
  rulesBackfilled?: number | null
  coveragePercent?: number | null
  triggeredRuleCount?: number | null
  disagreementCount?: number | null
  /** The level the agent proposed, before the deterministic scoring won. */
  agentRiskLevel?: RiskLevel | null
}

/** `GET /api/analyses/{assessmentId}`. */
export interface AnalysisResult extends AnalysisSummary, AnalysisCoverageCounters {
  summary: string | null
  recommendations: string | null
  ruleEvaluations: RuleEvaluation[]
  trace: TraceStep[]
}

/** Raw `GET /api/analyses/{assessmentId}` payload before normalisation. */
export interface AnalysisResultWire extends AnalysisSummary, AnalysisCoverageCounters {
  summary?: string | null
  recommendations?: string | null
  ruleEvaluations?: RuleEvaluationWire[] | null
  trace?: AnalysisTrace | JsonObject | JsonObject[] | string | null
}

/** `matchedTransactionIds` is defaulted to `[]` by `normalizeRuleEvaluation`. */
export interface RuleEvaluationWire extends Omit<RuleEvaluation, 'matchedTransactionIds'> {
  matchedTransactionIds?: UUID[] | null
}

/** Payload pushed over `GET /api/analyses/{assessmentId}/stream`. */
export interface AnalysisStreamEvent {
  assessmentId?: UUID
  status?: AnalysisStatus
  step?: TraceStep
  riskLevel?: RiskLevel | null
  totalScore?: number | null
  error?: string | null
}

/* -------------------------------------------------------------------------- */
/* Knowledge base / RAG                                                        */
/* -------------------------------------------------------------------------- */

export interface KnowledgeDocument {
  documentId: UUID
  filename: string
  title: string
  mimeType: string
  sizeBytes: number
  chunkCount: number
  status: KnowledgeDocumentStatus
  uploadedBy: string
  uploadedAt: IsoDateTime
  error?: string | null
}

export interface KnowledgeSearchRequest {
  query: string
  topK?: number
}

/**
 * One vector-store hit. Source attribution may arrive either flattened or
 * inside `metadata`; `normalizeChunk` in `api/knowledge` flattens it.
 */
export interface KnowledgeChunk {
  id?: string | null
  content: string
  score?: number | null
  documentId?: UUID | null
  filename?: string | null
  title?: string | null
  sectionTitle?: string | null
  chunkIndex?: number | null
  metadata?: JsonObject | null
}

/* -------------------------------------------------------------------------- */
/* Errors — RFC 7807 application/problem+json                                  */
/* -------------------------------------------------------------------------- */

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  /** Bean-validation field errors, when the backend supplies them. */
  errors?: Record<string, string[] | string> | null
  [key: string]: JsonValue | undefined
}
