/**
 * Wire types for the Customer Activity Analytics REST API.
 *
 * These mirror the REST contract and the ReAct trace. JSON is camelCase, ids
 * are UUID strings and timestamps are ISO-8601 UTC strings unless noted
 * otherwise.
 *
 * `threshold_logic` holds natural language: the agent reads the rule condition,
 * fetches the customer's data with its tools and judges it. Nothing parses that
 * column and nothing recomputes a verdict, so every score on these pages is a
 * model's estimate, capped at the rule's weight.
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

/**
 * Where a rule verdict came from — rendered on the coverage table.
 *
 * There is exactly one origin: `threshold_logic` is natural language, the agent
 * reads it, fetches the evidence and judges it. Nothing recomputes or overrules
 * that verdict, so the value is kept (and shown) to tell a reviewer plainly that
 * what they are reading is a model's judgement rather than a calculation.
 */
export const EVALUATION_SOURCES = ['AGENT_JUDGED'] as const
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
  /** Derived server-side from `dob`; also a field of the rule-editor catalog. */
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
   * The same `agg.*` values the agent reads through its tools, taken verbatim
   * from the customer's evaluation batch. Each is the PEAK of its rolling window
   * over the customer's whole history (the server folds every per-transaction
   * snapshot with `max`), which is the figure a rule condition naming a
   * threshold is judged against. They are not readings for the last 24 hours or
   * 30 days, and there is no single instant they are "as of" — the UI must label
   * them as peaks.
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
/* Risk rules — `threshold_logic` is natural language                          */
/* -------------------------------------------------------------------------- */

/**
 * `risk_rules.threshold_logic` is prose, not a machine-parseable expression.
 * The ReAct agent reads it verbatim, fetches the customer's data with its tools
 * and judges whether the rule is triggered and what score it contributes.
 *
 * Two consequences the UI must never hide:
 *  - the score for a rule is the agent's *estimate*, capped at `weight`;
 *  - two runs over identical data may reach different verdicts.
 */
export interface RiskRule {
  ruleId: UUID
  ruleName: string
  appliesTo: RuleScope
  /** The rule condition in plain English, exactly as the agent receives it. */
  thresholdLogic: string
  weight: number
}

/** `weight` is DECIMAL(5,2), so Jackson may serialise it as a string. */
export interface RiskRuleWire {
  ruleId: UUID
  ruleName: string
  appliesTo: RuleScope
  thresholdLogic?: string | null
  weight: number | string
}

export interface RiskRuleInput {
  ruleName: string
  appliesTo: RuleScope
  thresholdLogic: string
  weight: number
}

/** `risk_rules.rule_name` is VARCHAR(160). */
export const RULE_NAME_MAX_LENGTH = 160

/**
 * Character budget for a condition. The column is TEXT, so these mirror the
 * backend's `@Size(min = 20, max = 2000)` on `RuleUpsertRequest` rather than a
 * storage limit — the editor counts against them locally and still surfaces the
 * server's own field error if the two ever drift apart.
 *
 * The minimum is not pedantry: a one-word condition is not something a model can
 * judge, and it would still count against a run's coverage.
 */
export const RULE_CONDITION_MAX_LENGTH = 2000
export const RULE_CONDITION_MIN_LENGTH = 20

/** `weight` is DECIMAL(5,2), and the backend rejects anything outside this. */
export const RULE_WEIGHT_MIN = 0.01
export const RULE_WEIGHT_MAX = 999.99

/* -------------------------------------------------------------------------- */
/* Field catalog — GET /api/rules/field-catalog                                */
/* -------------------------------------------------------------------------- */

export const FIELD_TYPES = ['number', 'string', 'enum', 'boolean', 'datetime', 'date'] as const
/** Editor-side field type, lowercase — see `FieldCatalogEntryWire.type`. */
export type FieldType = (typeof FIELD_TYPES)[number]

/** Reference-panel grouping, in the order the panel renders them. */
export const FIELD_CATEGORIES = [
  'transaction',
  'customer',
  'card',
  'payment',
  'crypto',
  'aggregate',
] as const
export type FieldCategory = (typeof FIELD_CATEGORIES)[number]

/**
 * `GET /api/rules/field-catalog` as it arrives on the wire
 * (`RuleDtos.FieldCatalogEntry`).
 *
 * Every key except `field` is optional here on purpose: a reference panel that
 * renders a field with a missing label is far better than one that crashes.
 * `type` arrives as the Java enum name in UPPER CASE and `category` as a
 * lower-case string; when the category is missing `api/rules` derives it from
 * the field path (`agg.*` -> aggregate, `card.*` -> card, ...).
 */
export interface FieldCatalogEntryWire {
  field: string
  label?: string | null
  type?: string | null
  category?: string | null
  appliesTo?: RuleScope | null
  /** Known values of an enumerated field; empty when it is free-form. */
  options?: string[] | null
  nullable?: boolean | null
  description?: string | null
  /** A short sample value, e.g. `12500.00`. */
  example?: string | null
}

/** Normalised catalog entry — what the "available data" panel renders. */
export interface FieldCatalogEntry {
  field: string
  /** Human label; falls back to a title-cased form of the field path. */
  label: string
  type: FieldType
  category: FieldCategory
  /** Activity scope the field exists on; `ALL` when it is always available. */
  appliesTo: RuleScope
  description: string | null
  /** Allowed or suggested values; empty when the field is free-form. */
  options: string[]
  nullable: boolean
  example: string | null
}

/* -------------------------------------------------------------------------- */
/* Rule test — POST /api/rules/test                                            */
/* -------------------------------------------------------------------------- */

/**
 * A dry run of one draft rule against one customer. The backend answers by
 * asking the model to judge the condition, so this is a slow call (tens of
 * seconds) and a customer is mandatory — there is nothing to judge without one.
 */
export interface RuleTestRequest {
  ruleName: string
  thresholdLogic: string
  appliesTo: RuleScope
  weight: number
  customerId: UUID
}

export interface RuleTestMatchWire {
  transactionId: UUID
  activityType?: ActivityType | null
  amount?: number | string | null
  currency?: string | null
  status?: string | null
  createdAt?: IsoDateTime | null
  /** The model's note on why it counted this transaction. */
  reason?: string | null
}

export interface RuleTestMatch {
  transactionId: UUID
  activityType: ActivityType | null
  amount: number | null
  currency: string | null
  status: string | null
  createdAt: IsoDateTime | null
  reason: string | null
}

/**
 * `RuleDtos.RuleTestResponse`. `matchedCount` is the number of transactions the
 * model cited, which can exceed `matchedTransactions` when the backend truncates
 * the evidence it returns — the panel says so rather than under-reporting.
 */
export interface RuleTestResultWire {
  triggered?: boolean | null
  /** The model's estimated contribution, capped at the rule's weight. */
  score?: number | string | null
  weight?: number | string | null
  rationale?: string | null
  matchedTransactions?: RuleTestMatchWire[] | null
  matchedCount?: number | null
  evaluatedTransactionCount?: number | null
  customerName?: string | null
  model?: string | null
  durationMs?: number | null
  /** Corrections and caveats: evidence truncated, ids dropped, score capped. */
  notes?: string[] | null
}

/** Canonical verdict handed to the tester panel. */
export interface RuleTestResult {
  triggered: boolean
  score: number | null
  weight: number | null
  rationale: string | null
  matches: RuleTestMatch[]
  matchedCount: number
  /** Transactions in scope for the rule, when the backend reports it. */
  evaluatedCount: number | null
  /** True when the backend returned fewer evidence rows than it counted. */
  evidenceTruncated: boolean
  customerName: string | null
  model: string | null
  durationMs: number | null
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
  /** How many of the customer's transactions were in the rule's scope. */
  evaluatedTransactionCount?: number | null
  matchedCount?: number | null
  /** The transactions the agent cited as evidence. Empty, never absent. */
  matchedTransactionIds: UUID[]
  /** The agent's reasoning — the only account there is of why it decided this. */
  rationale?: string | null
  /** What the model asked for before the backend capped it at the rule's weight. */
  claimedScore?: number | null
  /** True when `claimedScore` exceeded the weight and `score` is the capped value. */
  scoreClamped?: boolean | null
}

export interface ToolCallTraceStep {
  type: 'tool_call'
  n: number
  tool: string
  args?: JsonValue
  resultPreview?: string | null
  ms?: number | null
  /**
   * What the call was about, recorded by the backend where the meaning was
   * known - the rule name for a verdict, the customer for a profile lookup.
   * Absent on runs stored before the labels existed, and on calls that have
   * nothing to name.
   */
  subject?: string | null
  /** How the call ended - `triggered +30.00 (rule 3 of 12)`, `2 of 4 transactions`. */
  outcome?: string | null
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

/**
 * The run ran out of steps with rules still unjudged, so it was recorded FAILED
 * rather than reported as a complete review. `missing` carries the rule ids and
 * `unjudgedRuleNames` the names, both taken from the step's `detail`.
 */
export interface CoverageFailedTraceStep {
  type: 'coverage_failed'
  n: number
  missing: string[]
  unjudgedRuleNames: string[]
  rulesTotal: number | null
  text: string
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
  | CoverageFailedTraceStep
  | FinalTraceStep
  | ErrorTraceStep
  | UnknownTraceStep

export interface AnalysisTrace {
  steps: TraceStep[]
}

/** Coverage counters the backend derives; rendered by the coverage panel. */
interface AnalysisCoverageCounters {
  /** Share of the coverage set that ended with a verdict; 100 on every COMPLETED run. */
  coveragePercent?: number | null
  triggeredRuleCount?: number | null
  /**
   * The band the model itself proposed, kept alongside `riskLevel` (which is the
   * sum of its own per-rule scores, banded) so the two can be compared.
   */
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
