import type {
  ChunkStrategy,
  DocumentStatus,
  PipelineStage,
} from "@/lib/api/types";

const pad = (n: number) => String(n).padStart(2, "0");

/** ISO 时间 → YYYY-MM-DD HH:mm */
export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fmtSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + " MB";
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " KB";
  return bytes + " B";
}

export function fmtScore(n: number | null | undefined): string {
  return n == null ? "—" : n.toFixed(3);
}

export function fmtMs(n: number | null | undefined): string {
  return n == null ? "—" : `${n} ms`;
}

/** 文档状态徽标文案（与原型一致） */
export const DOC_STATUS_META: Record<
  DocumentStatus,
  { label: string; variant: "neutral" | "info" | "success" | "danger" }
> = {
  QUEUED: { label: "排队中", variant: "neutral" },
  PROCESSING: { label: "处理中", variant: "info" },
  COMPLETED: { label: "已完成", variant: "success" },
  FAILED: { label: "失败", variant: "danger" },
};

/** 流水线阶段中文文案（与原型 stageSeq/statusMeta 一致） */
export const STAGE_LABEL: Record<PipelineStage, string> = {
  QUEUED: "排队中",
  PARSING: "解析中",
  CLEANING: "清洗中",
  CHUNKING: "分块中",
  EMBEDDING: "向量化中",
  INDEXING: "入库中",
  COMPLETED: "已完成",
};

/** 基本信息页签的阶段记录顺序（与原型一致） */
export const STAGE_SEQ: { stage: PipelineStage; name: string }[] = [
  { stage: "PARSING", name: "解析" },
  { stage: "CLEANING", name: "清洗" },
  { stage: "CHUNKING", name: "分块" },
  { stage: "EMBEDDING", name: "向量化" },
  { stage: "INDEXING", name: "入库" },
];

export const STRATEGY_META: Record<ChunkStrategy, { name: string; note: string }> = {
  LENGTH_OVERLAP: {
    name: "按长度切分 + 重叠",
    note: "按固定字符数切分，相邻分块保留重叠字符，适合无明确标题结构的文本。",
  },
  STRUCTURE: {
    name: "按标题结构切分",
    note: "按文档标题层级切分，保持同一段落语义完整；单块超过最大长度时再按长度截断。",
  },
};

export const FILE_TYPE_LABEL: Record<string, string> = {
  PDF: "PDF",
  MD: "Markdown",
  TXT: "TXT",
  DOCX: "Word",
  XLSX: "Excel",
  CSV: "CSV",
};

export const PROCESSING_STATUSES: DocumentStatus[] = ["QUEUED", "PROCESSING"];
