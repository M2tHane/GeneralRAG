package com.rag.api;

import com.rag.api.dto.ApiError;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常 → 契约 Error 响应体（{code, message, requestId, details}）的集中映射
 * （docs/03-技术路线.md §8）。兜底异常只回 INTERNAL_ERROR 文案，不向客户端泄漏堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 领域异常：使用异常自带的 HTTP 状态（默认取 ErrorCode 的契约映射）。 */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        // 业务去重/校验类失败按规范打 INFO 即可，不打堆栈。
        log.info("domain error code={} status={} message={}",
                ex.getCode(), ex.getHttpStatus().value(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiError.of(ex.getCode(), ex.getMessage(), toApiDetails(ex.getDetails())));
    }

    /** @Valid 请求体校验失败 → 400 INVALID_ARGUMENT，details 列出 field/issue。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<ApiError.Detail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toDetail)
                .toList();
        return badInvalidArgument(details);
    }

    /** 表单/查询参数绑定失败（BindException 是 MethodArgumentNotValidException 的父类分支）。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBind(BindException ex) {
        List<ApiError.Detail> details = ex.getFieldErrors().stream()
                .map(this::toDetail)
                .toList();
        return badInvalidArgument(details);
    }

    /**
     * 查询/路径参数类型转换失败（如 status 枚举值非法）→ 400 INVALID_ARGUMENT。
     * Task 4 补充：非法枚举筛选值属于客户端可修正的参数错误，不应落兜底 500。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(ErrorCode.INVALID_ARGUMENT, "请求参数不合法",
                        List.of(new ApiError.Detail(ex.getName(),
                                "无法解析的值：" + ex.getValue()))));
    }

    /** multipart 超限 → 413 FILE_TOO_LARGE。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(ErrorCode.FILE_TOO_LARGE,
                        "文件超过大小上限（50MB），请压缩后重试"));
    }

    /** 静态资源/未知路径 → 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.INVALID_ARGUMENT, "请求的资源不存在"));
    }

    /** 兜底：未知异常 → 500 INTERNAL_ERROR。log error 记录完整堆栈，客户端只看到固定文案。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后重试"));
    }

    private ResponseEntity<ApiError> badInvalidArgument(List<ApiError.Detail> details) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(ErrorCode.INVALID_ARGUMENT, "请求参数不合法", details));
    }

    private ApiError.Detail toDetail(FieldError fe) {
        String issue = fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "字段值不合法";
        return new ApiError.Detail(fe.getField(), issue);
    }

    private List<ApiError.Detail> toApiDetails(List<DomainException.Detail> details) {
        return details == null ? null
                : details.stream().map(d -> new ApiError.Detail(d.field(), d.issue())).toList();
    }
}
