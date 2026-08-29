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
 * Either Spring serialisation is accepted on the wire: the classic flattened
 * `Page` or the Boot 4 `PagedModel` with a nested `page` object.
 */
export interface SpringPage<T> {
  content: T[]
  page?: SpringPageMetadata
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

export interface Customer {
  customerId: UUID
  firstName: string
  lastName: string
  dob: IsoDate | null
  country: string
  /** Derived server-side from `dob` (also exposed as `customer.age` in the DSL). */
  age?: number | null
}

/** Row shape of `GET /api/customers` — core fields plus optional aggregates. */
export interface CustomerSummary {
  customerId: UUID
  firstName: string
  lastName: string
  dob?: IsoDate | null
  country: string
  age?: number | null
  transactionCount?: number | null
  totalAmount?: number | null
  lastActivityAt?: IsoDateTime | null
  lastRiskLevel?: RiskLevel | null
  lastAnalysisAt?: IsoDateTime | null
}

export interface CustomerSearchParams extends PageParams {
  /** Matches a customer UUID or a name fragment. */
  query?: string
}

/** One bucket of `ActivitySummary.byActivityType`. */
export interface ActivityTypeSummary {
  activityType: ActivityType
  count: number
  totalAmount: number
  avgAmount?: number | null
  maxAmount?: number | null
}

/** One bucket of `ActivitySummary.byStatus`. */
export interface StatusSummary {
  status: string
  count: number
  totalAmount?: number | null
}

/** `GET /api/customers/{customerId}/summary` — dashboard aggregates. */
export interface ActivitySummary {
  customerId: UUID
  totalTransactions: number
  totalAmount: number
  currencies: string[]
  countries: string[]
  byActivityType: ActivityTypeSummary[]
  byStatus: StatusSummary[]
  failedRatio?: number | null
  firstActivityAt?: IsoDateTime | null
  lastActivityAt?: IsoDateTime | null
  /** Velocity/aggregate features mirroring the `agg.*` DSL fields. */
  txCount24h?: number | null
  amountSum24h?: number | null
  failedCount24h?: number | null
  distinctCountries30d?: number | null
  cryptoRatio30d?: number | null
  maxAmount30d?: number | null
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
export type FieldType = (typeof FIELD_TYPES)[number]

/** `GET /api/rules/field-catalog` — drives the field/operator/value inputs. */
export interface FieldCatalogEntry {
  field: string
  type: FieldType
  /** Human label; falls back to the field path when absent. */
  label?: string | null
  notes?: string | null
  /** Allowed values for `enum` fields. May be encoded in `notes` instead. */
  values?: string[] | null
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
  activityType?: ActivityType
  amount?: number
  currency?: string
  status?: string
  createdAt?: IsoDateTime
}

/** `POST /api/rules/test`. */
export interface RuleTestResult {
  matchedCount: number
  sampleMatches: RuleTestMatch[]
  /** True when a leaf could not be evaluated (unknown field / type mismatch). */
  degraded: boolean
  evaluatedCount?: number | null
  message?: string | null
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

/** One row of `risk_assessments`, joined with its rule for display. */
export interface RuleEvaluation {
  ruleId: UUID
  ruleName: string
  appliesTo?: RuleScope | null
  weight?: number | null
  triggered: boolean
  /** `risk_assessments.score_contribution` — 0.00 when not triggered. */
  scoreContribution: number
  rationale?: string | null
  transactionIds: UUID[]
  source: EvaluationSource
  matchedCount?: number | null
  /** True when the agent verdict differed from the deterministic engine. */
  disagreement?: boolean | null
  triggeredAt?: IsoDateTime | null
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

/** `GET /api/analyses/{assessmentId}`. */
export interface AnalysisResult extends AnalysisSummary {
  summary: string | null
  recommendations: string | null
  ruleEvaluations: RuleEvaluation[]
  trace: TraceStep[]
}

/** Raw `GET /api/analyses/{assessmentId}` payload before normalisation. */
export interface AnalysisResultWire extends AnalysisSummary {
  summary?: string | null
  recommendations?: string | null
  ruleEvaluations?: RuleEvaluation[] | null
  trace?: AnalysisTrace | JsonObject | JsonObject[] | string | null
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
