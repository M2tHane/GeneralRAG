package com.rag.domain.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.rag.domain.enums.DatasetType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 评测数据集（V1__init.sql 表 7：eval_dataset）。
 * name 全局唯一（重复导入 → DUPLICATE_EVAL_DATASET）；仅元数据，
 * 样本内容在版本表中，导入产生新版本（EV-4）。
 */
@Entity
@Table(name = "eval_dataset")
public class EvalDatasetEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 调优集/测试集分离标记（EV-6）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_type", nullable = false, length = 16)
    private DatasetType datasetType;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DatasetType getDatasetType() {
        return datasetType;
    }

    public void setDatasetType(DatasetType datasetType) {
        this.datasetType = datasetType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
