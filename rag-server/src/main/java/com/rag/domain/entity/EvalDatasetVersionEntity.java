package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 评测数据集版本（V1__init.sql 表 8：eval_dataset_version）。
 * 导入产生新版本，旧版本不可变（EV-4 版本留痕），因此无 updated_at；
 * UNIQUE(dataset_id, version_no) 保证版本号单调不重复。
 */
@Entity
@Table(name = "eval_dataset_version")
public class EvalDatasetVersionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "dataset_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String datasetId;

    /** 版本号，同一数据集内从 1 递增。 */
    @Column(name = "version_no", nullable = false)
    private int versionNo;

    /** 该版本样本条数（导入时统计写入）。 */
    @Column(name = "item_count", nullable = false)
    private int itemCount = 0;

    /** 导入来源文件名（留痕，仅展示用途）。 */
    @Column(name = "source_name", nullable = false, length = 255)
    private String sourceName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
