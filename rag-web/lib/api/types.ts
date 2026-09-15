/**
 * 契约类型便捷导出（生成源：lib/api/schema.d.ts，来自 contracts/openapi.yaml）。
 */
import type { components } from "./schema";

export type Schemas = components["schemas"];

export type KnowledgeBase = Schemas["KnowledgeBase"];
export type KnowledgeBaseCreate = Schemas["KnowledgeBaseCreate"];
export type KnowledgeBaseUpdate = Schemas["KnowledgeBaseUpdate"];
export type DeletionSummary = Schemas["DeletionSummary"];
export type DocumentStatus = Schemas["DocumentStatus"];
export type PipelineStage = Schemas["PipelineStage"];
export type ChunkStrategy = Schemas["ChunkStrategy"];
export type ChunkingConfig = Schemas["ChunkingConfig"];
export type Document = Schemas["Document"];
export type DocumentDetail = Schemas["DocumentDetail"];
export type IngestionTask = Schemas["IngestionTask"];
export type UploadAccepted = Schemas["UploadAccepted"];
export type ParsedText = Schemas["ParsedText"];
export type Chunk = Schemas["Chunk"];
export type DocumentPage = Schemas["DocumentPage"];
export type ChunkPage = Schemas["ChunkPage"];
export type DocumentDeletionResult = Schemas["DocumentDeletionResult"];
export type Session = Schemas["Session"];
export type SessionCreate = Schemas["SessionCreate"];
export type Citation = Schemas["Citation"];
export type Message = Schemas["Message"];
export type MessagePage = Schemas["MessagePage"];
export type QAStreamRequest = Schemas["QAStreamRequest"];
export type QACancelRequest = Schemas["QACancelRequest"];
export type DebugRetrievalRequest = Schemas["DebugRetrievalRequest"];
export type DebugRetrievalResult = Schemas["DebugRetrievalResult"];
export type RetrievalHit = Schemas["RetrievalHit"];
export type DebugIssue = Schemas["DebugIssue"];
export type RetrievalMode = Schemas["RetrievalMode"];
export type RetrievalStageRank = Schemas["RetrievalStageRank"];

// ---------------- 评测（第二轮）----------------
export type DatasetType = Schemas["DatasetType"];
export type EvalCategory = Schemas["EvalCategory"];
export type EvidenceRef = Schemas["EvidenceRef"];
export type EvalDataset = Schemas["EvalDataset"];
export type EvalRunCreate = Schemas["EvalRunCreate"];
export type EvalRunAccepted = Schemas["EvalRunAccepted"];
export type EvalRunStatus = Schemas["EvalRunStatus"];
export type EvalRunSummary = Schemas["EvalRunSummary"];
export type EvalRunPage = Schemas["EvalRunPage"];
export type EvalHit = Schemas["EvalHit"];
export type EvalRunItem = Schemas["EvalRunItem"];
export type EvalRunItemPage = Schemas["EvalRunItemPage"];
export type EvalRunDetail = Schemas["EvalRunDetail"];
export type EvalReviewUpdate = Schemas["EvalReviewUpdate"];

/** 统一错误体 Error{code,message,requestId,details?} */
export interface ErrorBody {
  code: string;
  message: string;
  requestId: string;
  details?: { field?: string; issue: string }[];
}
