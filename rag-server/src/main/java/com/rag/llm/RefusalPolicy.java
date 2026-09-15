package com.rag.llm;

import java.util.List;

import com.rag.config.RagProperties;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import org.springframework.stereotype.Component;

/**
 * 拒答判定（R2-A2）：把"资料不足"从一句提示词变成显式、可配置、可测试的规则。
 *
 * <p><b>第一轮缺陷</b>：模型依 prompt 正确地说"资料不足"，但引用区仍挂着本次检索
 * 的命中（citations 在生成前就按 minScore 冻结），"拒答"与"有 6 条来源"自相矛盾。</p>
 *
 * <p><b>本轮规则</b>：本次检索的最高分低于阈值即判定证据不足 → 拒答、清空引用；
 * 低相关命中只在检索调试页可见。</p>
 *
 * <p><b>阈值分两套，按分数来源选择</b>（实测依据见 RagProperties.Refusal）：</p>
 * <ul>
 *   <li>重排相关度（HYBRID_RERANK 且未降级）：<b>有区分度</b>——可答题 ≥0.787、
 *       资料外 ≤0.630，用 {@code rerank-threshold}；</li>
 *   <li>余弦相似度（VECTOR / HYBRID / 重排降级）：<b>区分度弱</b>——资料外题
 *       0.65~0.77 与可答题区间重叠，用 {@code cosine-threshold}，只能过滤明显不相关。</li>
 * </ul>
 *
 * <p><b>如实声明的限制</b>：余弦阈值无法可靠区分资料外问题（这是数据本身的限制，
 * 不是调参不足）。因此评测报告必须分模式给出拒答正确率，不得用 VECTOR 模式的
 * 拒答数字代表系统能力。</p>
 */
@Component
public class RefusalPolicy {

    private final RagProperties.Refusal cfg;

    public RefusalPolicy(RagProperties ragProperties) {
        this.cfg = ragProperties.getRetrieval().getRefusal();
    }

    /**
     * 是否判定为证据不足（应拒答并清空引用）。
     *
     * @param hits 本次检索的命中（含未过阈值项）
     * @param mode 本次生效检索模式（决定阈值口径）
     * @param rerankApplied 重排是否真正生效（false 表示未启用或已降级 → 用余弦口径）
     */
    public boolean insufficient(List<RetrievalHit> hits, RetrievalMode mode, boolean rerankApplied) {
        if (!cfg.isEnabled()) {
            return false;
        }
        if (hits == null || hits.isEmpty()) {
            return true; // 完全没有命中：必然证据不足
        }
        double top = hits.stream().mapToDouble(RetrievalHit::score).max().orElse(0.0);
        return top < thresholdFor(mode, rerankApplied);
    }

    /** 本次生效阈值（供调试/评测快照与报告记录）。 */
    public double thresholdFor(RetrievalMode mode, boolean rerankApplied) {
        boolean useRerankScale = mode == RetrievalMode.HYBRID_RERANK && rerankApplied;
        return useRerankScale ? cfg.getRerankThreshold() : cfg.getCosineThreshold();
    }

    /** 阈值口径名（报告用，避免把两套阈值混为一谈）。 */
    public String scaleName(RetrievalMode mode, boolean rerankApplied) {
        boolean useRerankScale = mode == RetrievalMode.HYBRID_RERANK && rerankApplied;
        return useRerankScale ? "rerank" : "cosine";
    }

    /** 拒答文案（QA-6：明确说明无法依据当前资料回答）。 */
    public String refusalAnswer() {
        return REFUSAL_ANSWER;
    }

    public boolean enabled() {
        return cfg.isEnabled();
    }

    /** 固定拒答文案；与提示词中要求模型使用的措辞一致。 */
    public static final String REFUSAL_ANSWER = "当前资料不足以回答该问题。";
}
