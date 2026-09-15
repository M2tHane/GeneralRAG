package com.rag.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rag.domain.exception.ErrorCode;
import java.util.List;
import org.slf4j.MDC;

/**
 * 统一错误响应体，与 contracts/openapi.yaml components.schemas.Error 一致：
 * {code, message, requestId, details?: [{field?, issue}]}。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiError(String code, String message, String requestId, List<Detail> details) {

    public ApiError {
        details = details == null || details.isEmpty() ? null : List.copyOf(details);
    }

    public static ApiError of(ErrorCode code, String message, List<Detail> details) {
        return new ApiError(code.name(), message, MDC.get("requestId"), details);
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, null);
    }

    public record Detail(@JsonInclude(JsonInclude.Include.NON_NULL) String field, String issue) {
    }
}
