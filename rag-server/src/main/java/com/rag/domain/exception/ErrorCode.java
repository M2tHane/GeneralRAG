package com.rag.domain.exception;

/**
 * 稳定错误码全集（24 个，与 contracts/openapi.yaml components.schemas.Error 一字不差）。
 *
 * <p>每个枚举携带默认 HTTP 状态，映射依据契约各 endpoint 的 responses 定义；
 * 个别抛出点如需不同状态，可经 {@link DomainException} 构造器显式覆盖。</p>
 */
public enum ErrorCode {

    // ---------- 通用 ----------
    INVALID_ARGUMENT(400, "请求参数不合法"),
    CONFIRMATION_REQUIRED(400, "该操作需要显式确认（confirm=true）"),

    // ---------- 404 资源不存在 ----------
    KB_NOT_FOUND(404, "知识库不存在"),
    DOCUMENT_NOT_FOUND(404, "文档不存在"),
    SESSION_NOT_FOUND(404, "会话不存在"),
    EVAL_DATASET_NOT_FOUND(404, "评测数据集不存在"),
    EVAL_RUN_NOT_FOUND(404, "评测运行不存在"),
    CANCEL_TARGET_NOT_FOUND(404, "要取消的问答流不存在或已结束"),

    // ---------- 409 冲突 ----------
    KB_NAME_DUPLICATED(409, "同名知识库已存在"),
    DUPLICATE_DOCUMENT(409, "内容相同的文档已存在于该知识库"),
    PARSED_TEXT_NOT_READY(409, "解析文本尚未就绪（文档仍在入库处理中）"),
    TASK_NOT_RETRYABLE(409, "仅失败的任务可以重试"),
    DUPLICATE_EVAL_DATASET(409, "同名评测数据集已存在"),
    STREAM_ALREADY_ACTIVE(409, "同一 clientRequestId 的问答流正在进行中"),

    // ---------- 4xx 文件/数据 ----------
    UNSUPPORTED_FILE_TYPE(415, "不支持的文件类型（支持 pdf/md/txt/docx/xlsx/csv）"),
    FILE_TOO_LARGE(413, "文件超过大小上限"),
    SCANNED_PDF_NOT_SUPPORTED(422, "扫描版 PDF 暂不支持（未检测到文本层）"),
    INVALID_DATASET_FILE(422, "评测数据集文件格式不合法"),

    // ---------- 5xx 依赖/内部 ----------
    MODEL_TIMEOUT(504, "模型服务响应超时"),
    MODEL_UNAVAILABLE(502, "模型服务暂时不可用"),
    RETRIEVAL_FAILED(502, "检索服务暂时不可用"),
    STREAM_INTERRUPTED(500, "问答流被中断"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用（依赖未就绪或容量已满）"),
    PARSER_UNAVAILABLE(502, "解析服务暂时不可用"),
    INTERNAL_ERROR(500, "服务内部错误");

    private final int defaultHttpStatus;
    private final String defaultMessage;

    ErrorCode(int defaultHttpStatus, String defaultMessage) {
        this.defaultHttpStatus = defaultHttpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getDefaultHttpStatus() {
        return defaultHttpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
