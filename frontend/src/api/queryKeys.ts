import type {
  ActivityQueryParams,
  CustomerSearchParams,
  KnowledgeSearchRequest,
  UUID,
} from './types'

/**
 * Central query-key registry. Feature code should always build keys from here
 * so invalidation stays consistent across pages.
 */
export const queryKeys = {
  auth: {
    root: ['auth'] as const,
    me: () => ['auth', 'me'] as const,
  },
  customers: {
    root: ['customers'] as const,
    list: (params: CustomerSearchParams) => ['customers', 'list', params] as const,
    detail: (customerId: UUID) => ['customers', 'detail', customerId] as const,
    summary: (customerId: UUID) => ['customers', 'summary', customerId] as const,
    activity: (customerId: UUID, params: ActivityQueryParams) =>
      ['customers', 'activity', customerId, params] as const,
    analyses: (customerId: UUID) => ['customers', 'analyses', customerId] as const,
  },
  transactions: {
    root: ['transactions'] as const,
    detail: (transactionId: UUID) => ['transactions', 'detail', transactionId] as const,
  },
  analyses: {
    root: ['analyses'] as const,
    detail: (assessmentId: UUID) => ['analyses', 'detail', assessmentId] as const,
  },
  rules: {
    root: ['rules'] as const,
    list: () => ['rules', 'list'] as const,
    fieldCatalog: () => ['rules', 'field-catalog'] as const,
  },
  knowledge: {
    root: ['knowledge'] as const,
    documents: () => ['knowledge', 'documents'] as const,
    search: (request: KnowledgeSearchRequest) => ['knowledge', 'search', request] as const,
  },
  users: {
    root: ['users'] as const,
    list: () => ['users', 'list'] as const,
  },
  llmSettings: {
    root: ['llm-settings'] as const,
    settings: () => ['llm-settings', 'settings'] as const,
    reembedStatus: () => ['llm-settings', 'reembed-status'] as const,
  },
} as const
