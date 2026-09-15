package com.rag.domain.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 领域异常：携带稳定错误码、HTTP 状态、人读中文文案与可选的 details
 * （{field, issue} 数组，如 DUPLICATE_DOCUMENT 的 existingDocumentId）。
 *
 * <p>service/ingestion/retrieval 等层抛出本异常，由 api/GlobalExceptionHandler
 * 统一转换为契约的 Error 响应体。</p>
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus httpStatus;
    private final transient List<Detail> details;

    public DomainException(ErrorCode code) {
        this(code, code.getDefaultMessage());
    }

    public DomainException(ErrorCode code, String message) {
        this(code, HttpStatus.valueOf(code.getDefaultHttpStatus()), message, List.of());
    }

    public DomainException(ErrorCode code, String message, List<Detail> details) {
        this(code, HttpStatus.valueOf(code.getDefaultHttpStatus()), message, details);
    }

    public DomainException(ErrorCode code, HttpStatus httpStatus, String message, List<Detail> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public List<Detail> getDetails() {
        return details;
    }

    /** 字段级问题或附加信息，序列化为契约 Error.details 元素 {field, issue}。 */
    public record Detail(String field, String issue) {
    }
}
