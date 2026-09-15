package com.rag.domain.enums;

/**
 * 上传文件类型（contracts/openapi.yaml Document.fileType；V1__init.sql document.file_type）。
 *
 * <p>注：本枚举不在委派清单的 12 个枚举之列，但 document.file_type 列的取值
 * 需要类型安全承载，故按白名单（pdf/md/txt）补充；扩展名白名单校验
 * （ObjectStore#putSource）复用本枚举。</p>
 */
public enum FileType {
    PDF,
    MD,
    TXT,
    DOCX,
    XLSX,
    CSV
}
