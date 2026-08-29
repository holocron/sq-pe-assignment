export { ChunkResultCard, type ChunkResultCardProps } from './ChunkResultCard'
export { DocumentTable, type DocumentTableProps } from './DocumentTable'
export { DocumentUploader, type DocumentUploaderProps } from './DocumentUploader'
export { SimilarityMeter, type SimilarityMeterProps } from './SimilarityMeter'
export {
  normalizeSimilarity,
  similarityBand,
  type SimilarityBand,
} from './similarity'
export {
  documentTypeLabel,
  knowledgeStatusMeta,
  type KnowledgeStatusMeta,
} from './documentMeta'
export {
  ACCEPTED_KNOWLEDGE_LABEL,
  KNOWLEDGE_ACCEPT_ATTRIBUTE,
  MAX_KNOWLEDGE_FILE_BYTES,
  fileExtension,
  isAcceptedKnowledgeFile,
  validateKnowledgeDrop,
  validateKnowledgeFile,
  type FileAccepted,
  type FileRejected,
  type FileRejectionReason,
  type KnowledgeFileValidation,
} from './fileValidation'
export {
  escapeRegExp,
  extractQueryTerms,
  highlightSegments,
  type HighlightSegment,
} from './highlight'
