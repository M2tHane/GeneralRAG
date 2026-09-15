import { describe, expect, it } from "vitest";
import {
  checkComparability,
  compareItems,
  deltaArrow,
  fmtMetricValue,
  METRIC_META,
  metricDelta,
  normItem,
} from "@/lib/eval";

const meta = (key: string) => METRIC_META.find((m) => m.key === key)!;

describe("metricDelta", () => {
  it("比率升高记为更好，箭头随数值方向", () => {
    const d = metricDelta(meta("hitAt1"), 0.8, 0.9)!;
    expect(d.better).toBe(true);
    expect(d.text).toBe("+10.0pp");
    expect(deltaArrow(d)).toBe("▲");
  });

  it("比率下降记为变差", () => {
    const d = metricDelta(meta("mrr"), 0.9, 0.8)!;
    expect(d.better).toBe(false);
    expect(deltaArrow(d)).toBe("▼");
  });

  /**
   * 回归守护：耗时升高是"变差"，但箭头必须指向数值上升方向（▲），
   * 而不是因为"变差"就画成 ▼——否则出现「▼ +1290 ms」这种自相矛盾的展示。
   */
  it("耗时升高：箭头向上但判断为变差", () => {
    const d = metricDelta(meta("latencyP95Ms"), 3900, 6100)!;
    expect(d.better).toBe(false);
    expect(d.text).toBe("+2200 ms");
    expect(deltaArrow(d)).toBe("▲");
  });

  it("耗时下降为改善", () => {
    const d = metricDelta(meta("latencyP95Ms"), 6100, 3900)!;
    expect(d.better).toBe(true);
    expect(d.text).toBe("-2200 ms");
    expect(deltaArrow(d)).toBe("▼");
  });

  it("无变化时 flat 为真且显示破折号箭头", () => {
    const d = metricDelta(meta("hitAt1"), 0.9, 0.9)!;
    expect(d.flat).toBe(true);
    expect(deltaArrow(d)).toBe("–");
  });

  it("任一为空返回 null（未产出不等于 0）", () => {
    expect(metricDelta(meta("hitAt1"), null, 0.9)).toBeNull();
    expect(metricDelta(meta("hitAt1"), 0.9, null)).toBeNull();
  });
});

describe("fmtMetricValue", () => {
  it("null 显示破折号而不是 0", () => {
    expect(fmtMetricValue(meta("hitAt1"), null)).toBe("—");
    expect(fmtMetricValue(meta("latencyP95Ms"), null)).toBe("—");
  });

  it("比率显示百分比、耗时显示 ms", () => {
    expect(fmtMetricValue(meta("recallAt5"), 0.9286)).toBe("92.9%");
    expect(fmtMetricValue(meta("latencyP50Ms"), 3890)).toBe("3890 ms");
  });
});

describe("checkComparability", () => {
  const baseRun = {
    datasetVersionId: "v2",
    kbId: "kb-1",
    configSnapshot: {
      embeddingModel: "e",
      embeddingDimensions: 1024,
      chunkStrategy: ["STRUCTURE"],
      maxChunkChars: [800],
      retrievalMode: "HYBRID",
      topK: 6,
      minScore: 0.3,
      rrfK: 60,
      candidateLimit: 30,
      rerankEnabled: false,
      refusalEnabled: true,
    },
  };

  it("同版本同语料仅检索配置不同 → 可比", () => {
    const r = checkComparability(baseRun, {
      ...baseRun,
      configSnapshot: { ...baseRun.configSnapshot, retrievalMode: "HYBRID_RERANK", rerankEnabled: true },
    });
    expect(r.comparable).toBe(true);
    expect(r.sameRetrievalConfig).toBe(false);
  });

  it("数据集版本不同 → 不可比并列出差异字段", () => {
    const r = checkComparability(baseRun, { ...baseRun, datasetVersionId: "v1" });
    expect(r.comparable).toBe(false);
    expect(r.issues.map((i) => i.field)).toContain("数据集版本");
  });

  it("embedding 维度不同 → 不可比", () => {
    const r = checkComparability(baseRun, {
      ...baseRun,
      configSnapshot: { ...baseRun.configSnapshot, embeddingDimensions: 768 },
    });
    expect(r.comparable).toBe(false);
    expect(r.issues.map((i) => i.field)).toContain("向量维度");
  });

  it("分块配置不同 → 不可比（语料状态会独立影响指标）", () => {
    const r = checkComparability(baseRun, {
      ...baseRun,
      configSnapshot: { ...baseRun.configSnapshot, maxChunkChars: [1200] },
    });
    expect(r.comparable).toBe(false);
    expect(r.issues.map((i) => i.field)).toContain("分块配置");
  });

  it("配置完全相同 → sameRetrievalConfig 为真（用于提示「配置无差异」）", () => {
    const r = checkComparability(baseRun, { ...baseRun });
    expect(r.comparable).toBe(true);
    expect(r.sameRetrievalConfig).toBe(true);
  });
});

describe("compareItems", () => {
  const items = (spec: [number, boolean | null][]) =>
    spec.map(([seq, hit]) => ({
      seq,
      question: `q${seq}`,
      category: "DIRECT",
      hit,
      latencyMs: 1000 + seq,
    }));

  it("按 seq 关联并统计变好/变差/不变", () => {
    const base = items([[1, false], [2, true], [3, true], [4, true]]);
    const exp = items([[1, true], [2, false], [3, true], [4, true]]);
    const r = compareItems(base.map(normItem), exp.map(normItem));
    expect(r.better).toBe(1);
    expect(r.worse).toBe(1);
    expect(r.flat).toBe(2);
    expect(r.rows.find((x) => x.seq === 1)!.verdict).toBe("better");
    expect(r.rows.find((x) => x.seq === 2)!.verdict).toBe("worse");
  });

  it("hit 为 null（未判定）不参与变好/变差统计", () => {
    const base = items([[1, null]]);
    const exp = items([[1, true]]);
    const r = compareItems(base.map(normItem), exp.map(normItem));
    expect(r.rows[0].verdict).toBe("unknown");
    expect(r.better).toBe(0);
    expect(r.worse).toBe(0);
  });

  it("实验缺少该 seq 时记为不可比，不误判为退化", () => {
    const r = compareItems(items([[1, true]]).map(normItem), []);
    expect(r.rows[0].verdict).toBe("unknown");
    expect(r.worse).toBe(0);
  });
});
