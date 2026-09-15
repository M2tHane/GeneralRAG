package com.rag.answerability;

import java.util.List;

import com.rag.config.RagProperties;
import com.rag.llm.RefusalPolicy;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answerability 策略（R4 / R4.1 稳定化）：把"证据是否足以回答"从分数推断升级为显式判定，
 * <b>问答 / 调试 / 评测共用同一份真实逻辑</b>——这是 R2 确立的单一代码路径原则
 * 在判定层的延续。
 *
 * <p><b>门控形态来自 Baseline 数据（docs/round4/01-Baseline分析.md）</b>：
 * 72 题专项集上 PARTIAL_EVIDENCE 与可答题的 rerank 分布完全重叠（两类都有 1.000），
 * <b>不存在安全的"高分直答"阈值</b>，故采用：</p>
 *
 * <pre>
 * score &lt; lowThreshold（双口径沿用旧 RefusalPolicy 校准）
 *     → LOW_SCORE_REFUSAL（不调 Judge，零额外成本）
 * 其余（灰区 + 高分全部）
 *     → EvidenceSufficiencyJudge
 *         成功 → JUDGE_ACCEPT / JUDGE_REFUSE
 *         失败 → JUDGE_DEGRADED（failClosed=false 退回旧阈值行为并显式标注；
 *                 failClosed=true 保守拒答）
 * </pre>
 *
 * <p><b>与 RefusalPolicy 的关系</b>：RefusalPolicy 提供"低分直拒"的双口径阈值
 * 语义（rerank/cosine），本轮保留其作为门控第一步与降级回退路径；
 * AnswerabilityPolicy 在其上叠加 Judge。旧的"只有阈值判定"行为可通过
 * {@code rag.answerability.enabled=false} 完整还原（对照实验用）。</p>
 *
 * <p><b>R4.1 输入约定（Evidence 一致性）</b>：判定输入是 {@link AnswerabilityInput}，
 * 其中 {@code context} 必须是 <b>与生成共用同一个 Context 实例</b>——调用方
 * assemble 一次、判定与 Prompt 组装各取所需，不允许"Judge 前一次、生成前又一次"
 * 的重复组装或字数不一致的第二套证据。Judge 历史仅用于解析指代（见
 * {@link EvidenceSufficiencyJudge} 提示词声明），不构成证据。</p>
 */
@Component
public class AnswerabilityPolicy {

    private static final Logger log = LoggerFactory.getLogger(AnswerabilityPolicy.class);

    private final RagProperties ragProperties;
    private final RefusalPolicy refusalPolicy;
    private final EvidenceSufficiencyJudge judge;

    public AnswerabilityPolicy(RagProperties ragProperties,
                               RefusalPolicy refusalPolicy,
                               EvidenceSufficiencyJudge judge) {
        this.ragProperties = ragProperties;
        this.refusalPolicy = refusalPolicy;
        this.judge = judge;
    }

    public boolean enabled() {
        return ragProperties.getRetrieval().getAnswerability().isEnabled();
    }

    /**
     * 判定证据是否足以回答（R4.1：输入收敛为 {@link AnswerabilityInput}）。
     *
     * @return 判定结果（answerable=false 时调用方必须拒答并清空引用）
     */
    public AnswerabilityDecision evaluate(AnswerabilityInput input) {
        long start = System.currentTimeMillis();
        RagProperties.Answerability cfg = ragProperties.getRetrieval().getAnswerability();
        List<RetrievalHit> hits = input.hits();

        // 1. 关闭（对照模式）：行为=旧 RefusalPolicy 单阈值判定
        if (!cfg.isEnabled()) {
            boolean insufficient = refusalPolicy.insufficient(hits, input.mode(), input.rerankApplied());
            double threshold = refusalPolicy.thresholdFor(input.mode(), input.rerankApplied());
            String scale = refusalPolicy.scaleName(input.mode(), input.rerankApplied());
            double top = topScore(hits);
            return new AnswerabilityDecision(!insufficient,
                    AnswerabilityDecisionType.ANSWERABILITY_DISABLED,
                    null,
                    (insufficient ? "旧阈值拒答：" : "旧阈值放行：")
                            + "最高分 " + fmt(top) + "（" + scale + "）vs 阈值 " + fmt2(threshold),
                    false, false, System.currentTimeMillis() - start);
        }

        // 2. 零命中：必然无证据
        if (hits.isEmpty()) {
            return AnswerabilityDecision.noHits(System.currentTimeMillis() - start);
        }

        // 3. 低分直拒（沿用旧双阈值校准：rerank 0.65 / cosine 0.30）
        double top = topScore(hits);
        double lowThreshold = refusalPolicy.thresholdFor(input.mode(), input.rerankApplied());
        String scale = refusalPolicy.scaleName(input.mode(), input.rerankApplied());
        if (refusalPolicy.insufficient(hits, input.mode(), input.rerankApplied())) {
            return AnswerabilityDecision.lowScoreRefusal(top, lowThreshold, scale);
        }

        // 4. 灰区 + 高分全部交给 Judge；证据 = 与生成同一 Context 实例的全文（不截断）
        try {
            EvidenceSufficiencyJudge.JudgeResult result = judge.judge(
                    input.question(), input.history(), evidenceText(input));
            long latency = System.currentTimeMillis() - start;
            return result.answerable()
                    ? AnswerabilityDecision.judgeAccept(result.confidence(), result.reason(), latency)
                    : AnswerabilityDecision.judgeRefuse(result.confidence(), result.reason(), latency);
        } catch (EvidenceSufficiencyJudge.JudgeUnavailableException e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Judge 失败，按降级策略处理（failClosed={}）：{}", cfg.isFailClosed(), e.getMessage());
            if (cfg.isFailClosed()) {
                return AnswerabilityDecision.judgeDegraded(false,
                        "Judge 失败（failClosed 保守拒答）：" + e.getMessage(), latency);
            }
            // 退回旧策略语义：按旧阈值判定（此处必为"放行"，因为低分已在步骤 3 拒掉），
            // 但显式标注 JUDGE_DEGRADED——不虚构 Judge 结果，误判上界=旧系统本身
            return AnswerabilityDecision.judgeDegraded(true,
                    "Judge 失败，退回旧阈值行为放行（" + scale + " 阈值 " + fmt2(lowThreshold) + "）：" + e.getMessage(),
                    latency);
        }
    }

    /** Judge 证据文本：与生成完全同一份（Context.text），不做任何二次截断。 */
    private static String evidenceText(AnswerabilityInput input) {
        return input.context() == null ? "" : input.context().text();
    }

    private static double topScore(List<RetrievalHit> hits) {
        return hits.stream().mapToDouble(RetrievalHit::score).max().orElse(0.0);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
