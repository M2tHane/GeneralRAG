import { createSseParser } from "./sse";
import type {
  ChunkPage,
  ChunkStrategy,
  DebugRetrievalRequest,
  DebugRetrievalResult,
  DeletionSummary,
  Document,
  DocumentDeletionResult,
  DocumentDetail,
  DocumentPage,
  DocumentStatus,
  ErrorBody,
  EvalDataset,
  EvalReviewUpdate,
  EvalRunAccepted,
  EvalRunCreate,
  EvalRunDetail,
  EvalRunItem,
  EvalRunPage,
  IngestionTask,
  KnowledgeBase,
  KnowledgeBaseCreate,
  KnowledgeBaseUpdate,
  MessagePage,
  ParsedText,
  QACancelRequest,
  QAStreamRequest,
  Session,
  SessionCreate,
  UploadAccepted,
} from "./types";

/** 按 code 分支处理的 API 错误 */
export class ApiError extends Error {
  readonly code: string;
  readonly requestId: string;
  readonly details: ErrorBody["details"];
  readonly status: number;

  constructor(body: Partial<ErrorBody>, status: number) {
    super(body.message || `请求失败（HTTP ${status}）`);
    this.name = "ApiError";
    this.code = body.code || "INTERNAL_ERROR";
    this.requestId = body.requestId || "";
    this.details = body.details;
    this.status = status;
  }

  get detailByField(): string | undefined {
    return this.details?.find((d) => d.field)?.issue;
  }

  get existingDocumentId(): string | undefined {
    return this.details?.find((d) => d.issue.includes("existingDocumentId"))
      ?.issue;
  }
}

const BASE = "/api/v1";

async function request<T>(
  path: string,
  init?: RequestInit & { query?: Record<string, string | number | undefined> }
): Promise<T> {
  const { query, ...rest } = init ?? {};
  const url = new URL(BASE + path, window.location.origin);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, String(v));
    }
  }
  const res = await fetch(url.toString(), rest);
  if (!res.ok) {
    let body: Partial<ErrorBody> = {};
    try {
      body = await res.json();
    } catch {
      // 非 JSON 错误体（如代理 502 页面）
    }
    throw new ApiError(body, res.status);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

function json(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
}

// ---------------- 知识库 ----------------

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return request("/knowledge-bases");
}

export function createKnowledgeBase(body: KnowledgeBaseCreate): Promise<KnowledgeBase> {
  return request("/knowledge-bases", json("POST", body));
}

export function getKnowledgeBase(kbId: string): Promise<KnowledgeBase> {
  return request(`/knowledge-bases/${kbId}`);
}

export function updateKnowledgeBase(
  kbId: string,
  body: KnowledgeBaseUpdate
): Promise<KnowledgeBase> {
  return request(`/knowledge-bases/${kbId}`, json("PATCH", body));
}

export function deleteKnowledgeBase(kbId: string): Promise<DeletionSummary> {
  // 契约要求显式 confirm=true；前端确认 Modal 即用户确认
  return request(`/knowledge-bases/${kbId}?confirm=true`, { method: "DELETE" });
}

// ---------------- 文档 ----------------

export function listDocuments(
  kbId: string,
  params?: { status?: DocumentStatus; page?: number; pageSize?: number }
): Promise<DocumentPage> {
  return request(`/knowledge-bases/${kbId}/documents`, {
    query: {
      status: params?.status,
      page: params?.page,
      pageSize: params?.pageSize,
    },
  });
}

export function uploadDocument(
  kbId: string,
  file: File,
  opts: { chunkStrategy?: ChunkStrategy; maxLength?: number; overlap?: number }
): Promise<UploadAccepted> {
  const form = new FormData();
  form.append("file", file);
  if (opts.chunkStrategy) form.append("chunkStrategy", opts.chunkStrategy);
  if (opts.maxLength !== undefined) form.append("maxLength", String(opts.maxLength));
  if (opts.overlap !== undefined) form.append("overlap", String(opts.overlap));
  return request(`/knowledge-bases/${kbId}/documents`, {
    method: "POST",
    body: form,
  });
}

export function getDocument(docId: string): Promise<DocumentDetail> {
  return request(`/documents/${docId}`);
}

export function deleteDocument(docId: string): Promise<DocumentDeletionResult> {
  return request(`/documents/${docId}`, { method: "DELETE" });
}

export function getDocumentParsedText(
  docId: string,
  maxChars?: number
): Promise<ParsedText> {
  return request(`/documents/${docId}/parsed-text`, { query: { maxChars } });
}

export function listDocumentChunks(
  docId: string,
  params?: { page?: number; pageSize?: number }
): Promise<ChunkPage> {
  return request(`/documents/${docId}/chunks`, {
    query: { page: params?.page, pageSize: params?.pageSize },
  });
}

export function getDocumentTask(docId: string): Promise<IngestionTask> {
  return request(`/documents/${docId}/task`);
}

export function retryDocumentIngestion(docId: string): Promise<IngestionTask> {
  return request(`/documents/${docId}/retry`, { method: "POST" });
}

// ---------------- 版本管理（R3-P3） ----------------

export function listDocumentVersions(docId: string): Promise<Document[]> {
  return request(`/documents/${docId}/versions`);
}

export function activateDocumentVersion(docId: string): Promise<Document> {
  return request(`/documents/${docId}/activate`, { method: "POST" });
}

// ---------------- 会话 ----------------

export function listSessions(kbId?: string): Promise<Session[]> {
  return request("/sessions", { query: { kbId } });
}

export function createSession(body: SessionCreate): Promise<Session> {
  return request("/sessions", json("POST", body));
}

export function deleteSession(sessionId: string): Promise<void> {
  return request(`/sessions/${sessionId}`, { method: "DELETE" });
}

export function listMessages(
  sessionId: string,
  params?: { page?: number; pageSize?: number }
): Promise<MessagePage> {
  return request(`/sessions/${sessionId}/messages`, {
    query: { page: params?.page, pageSize: params?.pageSize },
  });
}

// ---------------- 问答 / 调试 ----------------

export function qaCancel(body: QACancelRequest): Promise<{ canceled: boolean }> {
  return request("/qa/cancel", json("POST", body));
}

export function debugRetrieval(
  body: DebugRetrievalRequest
): Promise<DebugRetrievalResult> {
  return request("/debug/retrieval", json("POST", body));
}

// ---------------- 评测（第二轮）----------------

export function listEvalDatasets(): Promise<EvalDataset[]> {
  return request("/eval/datasets");
}

export function importEvalDataset(form: FormData): Promise<EvalDataset> {
  // multipart：不设置 Content-Type，交给浏览器带 boundary
  return request("/eval/datasets", { method: "POST", body: form });
}

export function listEvalRuns(params?: {
  datasetId?: string;
  page?: number;
  pageSize?: number;
}): Promise<EvalRunPage> {
  return request("/eval/runs", { query: params as Record<string, string | number | undefined> });
}

export function createEvalRun(body: EvalRunCreate): Promise<EvalRunAccepted> {
  return request("/eval/runs", json("POST", body));
}

export function getEvalRun(
  runId: string,
  params?: { page?: number; pageSize?: number }
): Promise<EvalRunDetail> {
  return request(`/eval/runs/${encodeURIComponent(runId)}`, {
    query: params as Record<string, string | number | undefined>,
  });
}

export function reviewEvalRunItem(
  runId: string,
  itemId: string,
  body: EvalReviewUpdate
): Promise<EvalRunItem> {
  return request(
    `/eval/runs/${encodeURIComponent(runId)}/items/${encodeURIComponent(itemId)}`,
    json("PATCH", body)
  );
}

// ---------------- SSE 流式问答 ----------------

export interface StreamEventStage {
  stage: "RETRIEVAL_STARTED" | "RETRIEVAL_COMPLETED" | "GENERATION_STARTED";
  detail?: string;
  elapsedMs?: number;
}
export interface StreamEventToken {
  delta: string;
}
export interface StreamEventCitations {
  citations: import("./types").Citation[];
}
export interface StreamEventDone {
  messageId: string;
  elapsedMs?: number;
}
export interface StreamEventErrorPayload {
  code: string;
  message: string;
  requestId: string;
}
/** 契约 StreamEventError 的实际线上形状：错误体包在 error 键内。 */
interface StreamEventErrorEnvelope {
  error?: StreamEventErrorPayload;
}
export interface StreamEventCanceled {
  reason: "USER_CANCELED";
}

export interface QaStreamHandlers {
  onStage?(e: StreamEventStage): void;
  onToken?(e: StreamEventToken): void;
  onCitations?(e: StreamEventCitations): void;
  onDone?(e: StreamEventDone): void;
  onError?(e: StreamEventErrorPayload): void;
  onCanceled?(e: StreamEventCanceled): void;
}

/**
 * POST /api/v1/qa/stream 的 SSE 消费。
 * - HTTP 层错误（409 STREAM_ALREADY_ACTIVE / 404 等）抛 ApiError；
 * - 流内 error 事件回调 onError；canceled 事件或本地 abort 回调 onCanceled；
 * - 流意外中断（无终态事件即结束）回调 onError(STREAM_INTERRUPTED)。
 */
export async function streamQa(
  body: QAStreamRequest,
  handlers: QaStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch(`${BASE}/qa/stream`, {
    ...json("POST", body),
    signal,
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
  });

  if (!res.ok) {
    let errBody: Partial<ErrorBody> = {};
    try {
      errBody = await res.json();
    } catch {
      // 忽略非 JSON 错误体
    }
    throw new ApiError(errBody, res.status);
  }
  if (!res.body) {
    throw new ApiError(
      { code: "STREAM_INTERRUPTED", message: "连接未返回事件流", requestId: "" },
      res.status
    );
  }

  let terminal = false;
  const markTerminal = () => {
    terminal = true;
  };

  const dispatch = (event: string, data: string) => {
    let payload: unknown;
    try {
      payload = JSON.parse(data);
    } catch {
      return; // 非法载荷忽略，等待终态
    }
    switch (event) {
      case "stage":
        handlers.onStage?.(payload as StreamEventStage);
        break;
      case "token":
        handlers.onToken?.(payload as StreamEventToken);
        break;
      case "citations":
        handlers.onCitations?.(payload as StreamEventCitations);
        break;
      case "done":
        markTerminal();
        handlers.onDone?.(payload as StreamEventDone);
        break;
      case "error": {
        markTerminal();
        // 契约（与后端一致）把错误体包在 error 键内：{error:{code,message,requestId}}。
        // 兼容两种形状，避免上游省略包装时把 undefined 显示给用户。
        const env = payload as StreamEventErrorEnvelope & StreamEventErrorPayload;
        const errPayload: StreamEventErrorPayload = env.error
          ? env.error
          : (payload as StreamEventErrorPayload);
        handlers.onError?.(errPayload);
        break;
      }
      case "canceled":
        markTerminal();
        handlers.onCanceled?.(payload as StreamEventCanceled);
        break;
      default:
        break; // 未知事件类型忽略，向前兼容
    }
  };

  const parser = createSseParser((e) => dispatch(e.event, e.data));
  const reader = res.body.getReader();
  const decoder = new TextDecoder();

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
    }
    parser.push(decoder.decode());
    parser.end();
  } catch (err) {
    if (signal?.aborted) {
      if (!terminal) handlers.onCanceled?.({ reason: "USER_CANCELED" });
      return; // 本地主动取消不是异常
    }
    throw err;
  } finally {
    reader.releaseLock();
  }

  if (!terminal) {
    handlers.onError?.({
      code: "STREAM_INTERRUPTED",
      message: "连接中断，回答未完成。你的问题已保留，可重试。",
      requestId: "",
    });
  }
}
