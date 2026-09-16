package com.rag.retrieval;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalStages;
import com.rag.storage.es.EsHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DashScope 文本重排适配器（R2-R1）。
 *
 * <p><b>为什么不是 OpenAI 兼容客户端</b>：实测 {@code /compatible-mode/v1/rerank}
 * 返回 404，重排仅提供原生端点
 * {@code POST /api/v1/services/rerank/text-rerank/text-rerank}，请求体为
 * {@code {model, input:{query, documents}, parameters:{top_n}}}，
 * 响应为 {@code {output:{results:[{index, relevance_score}]}}}。
 * 因此这里直接用 JDK HttpClient，不引入额外 SDK。</p>
 *
 * <p><b>失败即降级</b>（R2-R2）：超时、非 2xx、响应结构异常、无候选等一律返回
 * {@link RerankOutcome#degraded} 而非抛异常——问答可用性优先；降级必须可被上层
 * 标注，避免"静默返回更差排序并计入评测对比"。</p>
 *
 * <p>仅在 {@code rag.retrieval.rerank.base-url} 非空时装配；未配置时由
 * {@link RetrievalConfig} 提供 NoOp 实现（VECTOR/HYBRID 模式不需要重排）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "rag.retrieval.rerank", name = "base-url")
public class DashScopeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);

    /**
     * 重排端点路径：base-url 若已含完整 path（以 text-rerank 结尾）则直接使用，
     * 否则补上原生路径。兼容用户把 base-url 写成域名根或写成完整端点两种习惯。
     */
    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final RagProperties.Rerank cfg;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DashScopeReranker(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.cfg = ragProperties.getRetrieval().getRerank();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, cfg.getTimeoutSeconds())))
                .build();
    }

    @Override
    public RerankOutcome rerank(String kbId, String question, List<RetrievalHit> hits) {
        // R2-R3：空候选不发起外部调用（厂商对空 documents 返回 400）
        if (hits == null || hits.isEmpty()) {
            return RerankOutcome.applied(List.of());
        }
        if (question == null || question.isBlank()) {
            return RerankOutcome.degraded(hits, "问题为空，跳过重排");
        }
        try {
            // R5-A：reranker 只看检索增强表示（旧数据无 retrieval_content 时回退 answerContent）
            List<String> documents = new ArrayList<>(hits.size());
            for (RetrievalHit hit : hits) {
                documents.add(truncate(hit.chunk().retrievalContent() != null
                        ? hit.chunk().retrievalContent() : hit.chunk().content()));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.getModelName());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", question);
            input.put("documents", documents);
            body.put("input", input);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("top_n", hits.size());
            body.put("parameters", parameters);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(resolveEndpoint()))
                    .timeout(Duration.ofSeconds(Math.max(1, cfg.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)));
            if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + cfg.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("重排服务返回非 2xx（status={}），降级为融合顺序", response.statusCode());
                return RerankOutcome.degraded(hits, "重排服务返回 HTTP " + response.statusCode());
            }
            return applyScores(hits, objectMapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("重排调用被中断，降级为融合顺序");
            return RerankOutcome.degraded(hits, "重排调用被中断");
        } catch (Exception e) {
            log.warn("重排调用失败（{}），降级为融合顺序", e.getMessage());
            return RerankOutcome.degraded(hits, "重排调用失败：" + e.getMessage());
        }
    }

    /**
     * 把 {@code {index, relevance_score}} 映射回候选并按其降序重排。
     * index 是请求 documents 的下标（不是 chunkId），映射错位会静默给出错误来源，
     * 因此越界/缺失一律视为响应异常 → 降级。
     */
    private RerankOutcome applyScores(List<RetrievalHit> hits, JsonNode root) {
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            return RerankOutcome.degraded(hits, "重排响应缺少 output.results");
        }
        Map<Integer, Double> scoreByIndex = new HashMap<>();
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= hits.size()) {
                return RerankOutcome.degraded(hits, "重排响应 index 越界：" + index);
            }
            scoreByIndex.put(index, node.path("relevance_score").asDouble(Double.NaN));
        }
        List<IndexedHit> scored = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Double score = scoreByIndex.get(i);
            if (score == null || score.isNaN()) {
                return RerankOutcome.degraded(hits, "重排响应缺少第 " + i + " 条的相关度");
            }
            scored.add(new IndexedHit(i, score, hits.get(i)));
        }
        // 相关度降序；并列时保持候选原序（确定性）
        scored.sort(Comparator.comparingDouble(IndexedHit::score).reversed()
                .thenComparingInt(IndexedHit::originalIndex));

        List<RetrievalHit> reranked = new ArrayList<>(scored.size());
        int rank = 1;
        for (IndexedHit item : scored) {
            RetrievalHit hit = item.hit();
            EsHit chunk = hit.chunk();
            RetrievalStages stages = hit.stages();
            RetrievalStages updated = new RetrievalStages(
                    stages.vectorRank(), stages.vectorScore(),
                    stages.bm25Rank(), stages.bm25Score(),
                    stages.fusedRank(), stages.fusedScore(),
                    rank, item.score(), false);
            // 最终排序分 = 重排相关度（决定 rank 与阈值判定）
            reranked.add(new RetrievalHit(rank, chunk, item.score(), hit.passedThreshold(), updated));
            rank++;
        }
        return RerankOutcome.applied(reranked);
    }

    private String resolveEndpoint() {
        String base = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("text-rerank")) {
            return base;
        }
        return base + RERANK_PATH;
    }

    /** 单条候选正文截断，控制 token 成本（厂商按输入 token 计费）。 */
    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_DOC_CHARS ? content : content.substring(0, MAX_DOC_CHARS);
    }

    private static final int MAX_DOC_CHARS = 800;

    private record IndexedHit(int originalIndex, double score, RetrievalHit hit) {
    }
}
