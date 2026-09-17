package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.rag.domain.enums.DocumentStatus;
import com.rag.domain.enums.FileType;
import com.rag.domain.enums.PipelineStage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 文档元数据（V1__init.sql 表 2：document）。
 *
 * <p>UNIQUE(kb_id, content_sha256) 落实 KB 范围内容去重（KB-8），
 * 重复上传在建行前即命中约束 → 409 DUPLICATE_DOCUMENT。
 * chunk_config 为 JSON 列（结构同契约 ChunkingConfig）；
 * 分块正文不进 MySQL，仅存 ES（见路线文档 §4.1）。</p>
 *
 * <p>多版本（R3-P3，V3__document_versions.sql）：root_id/version_no/is_active
 * 三列承载同源文档的版本组——同名不同内容再上传形成新版本并自动激活；
 * 检索只命中激活版本（RetrievalPipeline 阶段 4.5 过滤）。组内至多一个
 * is_active=TRUE 由 uk_document_active（生成列 active_key）唯一索引兜底。</p>
 */
@Entity
@Table(name = "document")
public class DocumentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "kb_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String kbId;

    /** 用户上传的原始文件名，仅展示，不参与对象键拼接（路线 §7 安全约束）。 */
    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 8)
    private FileType fileType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** 文件内容 SHA-256 十六进制小写（64 位），KB 范围去重依据。 */
    @Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String contentSha256;

    /** 同源文档组标识 = 组内第一版 document.id（R3-P3）；第一版 root_id = id。 */
    @Column(name = "root_id", nullable = false, columnDefinition = "char(36)")
    private String rootId;

    /** 组内版本号，从 1 递增（同 root_id 内唯一，R3-P3）。 */
    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    /** 组内激活版本（检索只命中激活版本）；组内至多一个 TRUE（R3-P3）。 */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 16)
    private PipelineStage currentStage;

    /** 已完成分块数量（聚合值；分块正文仅存 ES）。 */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    /** 生效的分块策略与参数，结构同契约 ChunkingConfig{strategy,maxLength,overlap}。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_config", columnDefinition = "json")
    private Map<String, Object> chunkConfig;

    /** MinIO 源对象键（ragsource/{kbId}/{docId}/source.*），仅 UUID 组成。 */
    @Column(name = "source_object_key", length = 255)
    private String sourceObjectKey;

    /** MinIO 解析+清洗产物对象键；存在即 PARSING 阶段完成标志（重试据此跳过）。 */
    @Column(name = "parsed_object_key", length = 255)
    private String parsedObjectKey;

    /**
     * 解析路由元数据（R6-D/R6-D.1，JSON 列）：仅 PDF 文档写入，非 PDF 恒 null。
     * AUTO 模式——{parser:{requested:"AUTO",selected,routingReason,probe:{...}}}，
     * 正式解析成功或失败（routing decision 已产生）都持久化；
     * 手动模式——requested=selected=<模式>，routingReason=null，无 probe
     * （记录文档实际由哪个 parser 解析）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parse_metadata", columnDefinition = "json")
    private Map<String, Object> parseMetadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 16)
    private PipelineStage failureStage;

    /** 失败原因（稳定错误码 + 人读文案）；仅 status=FAILED 时非空。 */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        // 未显式归组的插入（测试/内部路径）默认自成一版组 v1（R3-P3）
        if (rootId == null || rootId.isBlank()) {
            rootId = id;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FileType getFileType() {
        return fileType;
    }

    public void setFileType(FileType fileType) {
        this.fileType = fileType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public String getRootId() {
        return rootId;
    }

    public void setRootId(String rootId) {
        this.rootId = rootId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public PipelineStage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(PipelineStage currentStage) {
        this.currentStage = currentStage;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Map<String, Object> getChunkConfig() {
        return chunkConfig;
    }

    public void setChunkConfig(Map<String, Object> chunkConfig) {
        this.chunkConfig = chunkConfig;
    }

    public String getSourceObjectKey() {
        return sourceObjectKey;
    }

    public void setSourceObjectKey(String sourceObjectKey) {
        this.sourceObjectKey = sourceObjectKey;
    }

    public String getParsedObjectKey() {
        return parsedObjectKey;
    }

    public void setParsedObjectKey(String parsedObjectKey) {
        this.parsedObjectKey = parsedObjectKey;
    }

    public Map<String, Object> getParseMetadata() {
        return parseMetadata;
    }

    public void setParseMetadata(Map<String, Object> parseMetadata) {
        this.parseMetadata = parseMetadata;
    }

    public PipelineStage getFailureStage() {
        return failureStage;
    }

    public void setFailureStage(PipelineStage failureStage) {
        this.failureStage = failureStage;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
