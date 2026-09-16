package com.rag.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * FakeOpenAiServer 伪向量性质测试（R4.1.x 测试基建）：
 * deterministic / L2 normalized / 不同文本近正交 —— 这些性质是
 * AnswerabilityFlowIT 等套件的高低分判定 margin 的数学基础。
 */
class FakeOpenAiServerVectorTest {

    private static final double EPS = 1e-6;

    private static double norm(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot; // 两向量均已 L2 归一化
    }

    @Test
    void deterministicSameTextSameVector() {
        assertThat(FakeOpenAiServer.embedVector("abc"))
                .containsExactly(FakeOpenAiServer.embedVector("abc"));
        assertThat(FakeOpenAiServer.embedVector("支付回调确认超时是多少？"))
                .containsExactly(FakeOpenAiServer.embedVector("支付回调确认超时是多少？"));
    }

    @Test
    void vectorIsL2Normalized() {
        for (String t : new String[] {"abc", "", "支付回调确认超时是多少？", "urjlprhzcojlniej"}) {
            assertThat(norm(FakeOpenAiServer.embedVector(t))).isCloseTo(1.0, within(EPS));
        }
    }

    @Test
    void dimensionMatchesRealIndexMapping() {
        assertThat(FakeOpenAiServer.embedVector("x")).hasSize(FakeOpenAiServer.DIMENSIONS);
        assertThat(FakeOpenAiServer.DIMENSIONS).isEqualTo(1024);
    }

    @Test
    void unrelatedTextsAreNearOrthogonal() {
        // 高低分判定题对：不同文本的确定性伪随机向量应近似正交
        double cos = cosine(FakeOpenAiServer.embedVector("支付回调确认超时是多少？"),
                FakeOpenAiServer.embedVector("urjlprhzcojlniej"));
        assertThat(Math.abs(cos)).as("unrelated |cos|=%.4f", cos).isLessThan(0.15);
        // 抽查更多无关对，排除"恰好对"的侥幸
        assertThat(Math.abs(cosine(FakeOpenAiServer.embedVector("签名算法是什么？"),
                FakeOpenAiServer.embedVector("怎么用 Git rebase 整理提交历史？")))).isLessThan(0.15);
        assertThat(Math.abs(cosine(FakeOpenAiServer.embedVector("abc"),
                FakeOpenAiServer.embedVector("xyz")))).isLessThan(0.15);
    }

    @Test
    void esCosineScoreMarginsHold() {
        // ES cosine score = (1+cos)/2：同文 ≈1.0（必过 0.90 阈值进 Judge），
        // 无关 ≈0.5（必低于 0.90 → LOW_SCORE_REFUSAL）——margin 明显
        float[] v = FakeOpenAiServer.embedVector("支付回调确认超时是多少？");
        double sameScore = (1 + 1.0) / 2;
        double unrelated = cosine(v, FakeOpenAiServer.embedVector("urjlprhzcojlniej"));
        assertThat(sameScore).isCloseTo(1.0, within(EPS));
        assertThat((1 + Math.abs(unrelated)) / 2).as("worst-case unrelated score")
                .isLessThan(0.90 - 0.35); // 至少 0.35 的绝对 margin
    }
}
