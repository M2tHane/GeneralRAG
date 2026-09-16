package com.rag.domain.enums;

/**
 * Hard Eval 负例失败机理（R6-A，contracts/openapi.yaml EvalFailureMode）。
 *
 * <p>仅用于 {@code answerable=false} 的样本，回答「系统为什么会误答这道题」：
 * 诊断负例时按机理分组统计 FAR，比整体 FAR 更能定位该优化哪一层。
 * {@code answerable=true} 的样本该值必须为 null——两个字段不允许矛盾状态。</p>
 *
 * <ul>
 *   <li>{@link #OUT_OF_KB}：知识库完全没有相关内容（最基础的拒答负例）；</li>
 *   <li>{@link #PARTIAL_EVIDENCE}：语料只覆盖问题要求的部分答案（高相关但不可回答）；</li>
 *   <li>{@link #MISSING_CONDITION}：缺少关键条件/前提（如"默认情况下"缺隔离级别）；</li>
 *   <li>{@link #ENTITY_MISMATCH}：相关词很多但实体不一致（如 Producer 问成 Consumer）；</li>
 *   <li>{@link #SCOPE_MISMATCH}：范围/对象不一致（如问集群级却只给了节点级）；</li>
 *   <li>{@link #NUMERIC_MISMATCH}：数值/单位陷阱（默认值 vs 最大值 vs 推荐值、秒 vs 毫秒）；</li>
 *   <li>{@link #VERSION_CONFLICT}：新旧行为混淆（必须以当前 active 行为为准）；</li>
 *   <li>{@link #UNSUPPORTED_INFERENCE}：证据只支持 A→B，问题却问 B→A 等反向/无据推断。</li>
 * </ul>
 */
public enum EvalFailureMode {
    OUT_OF_KB,
    PARTIAL_EVIDENCE,
    MISSING_CONDITION,
    ENTITY_MISMATCH,
    SCOPE_MISMATCH,
    NUMERIC_MISMATCH,
    VERSION_CONFLICT,
    UNSUPPORTED_INFERENCE
}
