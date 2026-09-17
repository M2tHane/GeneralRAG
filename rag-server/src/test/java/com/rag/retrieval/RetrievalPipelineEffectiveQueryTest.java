package com.rag.retrieval;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.config.RagProperties;
import com.rag.domain.entity.DocumentEntity;
import com.rag.llm.PromptAssembler;
import com.rag.retrieval.model.RetrievalHit;
import com.rag.retrieval.model.RetrievalMode;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.es.EsHit;
import com.rag.storage.repository.DocumentRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * R6-C.1 Effective Retrieval Query 不变性测试（纯单测，recording reranker）。
 *
 * <p><b>核心命题</b>：一次检索执行只有一个 effective query——Embedding、BM25、
 * Reranker 三者输入必须同源；{@code RetrievalTrace.queryRewrite.retrievalQuery}
 * 必须等于 Reranker 实际收到的 query（否则 Debug/Eval 归因失真）。</p>
 *
 * <p>本测试由 R6-C.1 的 correctness bug 驱动：修复前 RetrievalPipeline 把
 * {@code request.question()}（原始问题）传给 reranker，而 Embedding/BM25 用
 * 改写后查询——rewrite=true 时 reranker 收到的 query ≠ trace.retrievalQuery。</p>
 */
@ExtendWith(MockitoExtension.class)
class RetrievalPipelineEffectiveQueryTest {

    /** 记录 reranker 实际收到的 query（invariant 断言核心）。 */
    static final class RecordingReranker implements Reranker {
        String receivedKbId;
        String receivedQuery;
        List<RetrievalHit> receivedHits;
        int invoked;

        @Override
        public RerankOutcome rerank(String kbId, String question, List<RetrievalHit> hits) {
            invoked++;
            this.receivedKbId = kbId;
            this.receivedQuery = question;
            this.receivedHits = hits;
            return RerankOutcome.applied(hits);
        }
    }

    @Mock EmbeddingModel embeddingModel;
    @Mock EsChunkIndex esChunkIndex;
    @Mock DocumentRepository documentRepository;

    private RagProperties props;
    private RecordingReranker reranker;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getRetrieval().setMode(RetrievalMode.HYBRID_RERANK);
        reranker = new RecordingReranker();
    }

    private RetrievalPipeline pipeline(QueryRewriteService rewriter) {
        return new RetrievalPipeline(embeddingModel, esChunkIndex, new RrfFusion(),
                reranker, documentRepository, props, rewriter);
    }

    private QueryRewriteService rewriterAlways(String json) {
        // 用 stub ChatModel 驱动真实 parse 逻辑（不 mock Rewriter 本身，保持 parse 语义）
        dev.langchain4j.model.chat.ChatModel stub = new dev.langchain4j.model.chat.ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(json))
                        .build();
            }
        };
        return new QueryRewriteService(stub, props, new ObjectMapper());
    }

    private void stubRetrievalGreen() {
        // embedding：任意文本返回 1024 维零向量（本项目 langchain4j 版本 embed 返回
        // Response<Embedding> 包装，与 RetrievalPipeline.embed 的解包方式一致）
        lenient().when(embeddingModel.embed(anyString())).thenAnswer(inv ->
                new dev.langchain4j.model.output.Response<>(
                        new dev.langchain4j.data.embedding.Embedding(new float[1024])));
        // ES：向量/BM25 各返回一个稳定候选（内容决定 EvidenceMatcher 锚点）
        EsHit v = new EsHit("c-vector", "doc1", "文档 > 向量命中", null, 0, 10,
                "向量通道候选内容", 0.8);
        EsHit b = new EsHit("c-bm25", "doc1", "文档 > BM25命中", null, 1, 10,
                "BM25 通道候选内容", 0.7);
        lenient().when(esChunkIndex.knnSearch(anyString(), any(float[].class), anyInt(), anyInt()))
                .thenReturn(List.of(v));
        lenient().when(esChunkIndex.bm25Search(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(b));
        // 有效性过滤：文档存在且激活
        DocumentEntity doc = new DocumentEntity();
        doc.setId("doc1");
        doc.setActive(true);
        lenient().when(documentRepository.findAllById(any())).thenReturn(List.of(doc));
    }

    private static final String ORIGINAL = "前者是多少？";
    private static final String REWRITTEN = "HikariCP idleTimeout 默认是多少？";
    private static final List<PromptAssembler.HistoryTurn> HISTORY = List.of(
            new PromptAssembler.HistoryTurn(com.rag.domain.enums.SessionRole.USER,
                    "HikariCP 里 idleTimeout 和 cachePrepStmts 分别是什么默认值？"));

    // ---------- rewrite=true ----------

    @Test
    void rewriteTrue_rerankerAndTraceUseRewrittenQuery() {
        stubRetrievalGreen();
        QueryRewriteService rewriter = rewriterAlways(
                "{\"rewritten\": true, \"query\": \"" + REWRITTEN + "\"}");
        RetrievalPipeline pipeline = pipeline(rewriter);

        RetrievalPipeline.RetrievalOutcome outcome = pipeline.execute(
                new RetrievalRequest("kb1", ORIGINAL, null, null, null, null, HISTORY));

        // 核心不变性 1：reranker 收到的 query == 改写后查询（修复前这里收到 ORIGINAL）
        assertThat(reranker.invoked).isEqualTo(1);
        assertThat(reranker.receivedQuery).isEqualTo(REWRITTEN);
        // 核心不变性 2：trace.retrievalQuery == reranker query（Debug/Eval 归因可信）
        assertThat(outcome.trace().queryRewrite()).isNotNull();
        assertThat(outcome.trace().queryRewrite().rewritten()).isTrue();
        assertThat(outcome.trace().queryRewrite().retrievalQuery()).isEqualTo(REWRITTEN);
        assertThat(outcome.trace().queryRewrite().retrievalQuery()).isEqualTo(reranker.receivedQuery);
        // Embedding/BM25 也必须用同一 effective query
        org.mockito.Mockito.verify(embeddingModel).embed(REWRITTEN);
        org.mockito.Mockito.verify(esChunkIndex).bm25Search(eq("kb1"), eq(REWRITTEN), anyInt());
    }

    // ---------- rewrite=false（Rewriter 判定保持原问题） ----------

    @Test
    void rewriteFalse_rerankerAndTraceUseOriginalQuestion() {
        stubRetrievalGreen();
        QueryRewriteService rewriter = rewriterAlways("{\"rewritten\": false}");
        RetrievalPipeline pipeline = pipeline(rewriter);

        RetrievalPipeline.RetrievalOutcome outcome = pipeline.execute(
                new RetrievalRequest("kb1", ORIGINAL, null, null, null, null, HISTORY));

        assertThat(reranker.receivedQuery).isEqualTo(ORIGINAL);
        assertThat(outcome.trace().queryRewrite().rewritten()).isFalse();
        assertThat(outcome.trace().queryRewrite().retrievalQuery()).isEqualTo(ORIGINAL);
        assertThat(outcome.trace().queryRewrite().retrievalQuery()).isEqualTo(reranker.receivedQuery);
    }

    // ---------- rewrite fallback（模型失败回退原问题） ----------

    @Test
    void rewriteFallback_rerankerAndTraceUseOriginalQuestion() {
        stubRetrievalGreen();
        // Rewriter 输出非法 JSON → INVALID_RESPONSE → fallback original
        QueryRewriteService rewriter = rewriterAlways("这不是 JSON");
        RetrievalPipeline pipeline = pipeline(rewriter);

        RetrievalPipeline.RetrievalOutcome outcome = pipeline.execute(
                new RetrievalRequest("kb1", ORIGINAL, null, null, null, null, HISTORY));

        assertThat(reranker.receivedQuery).isEqualTo(ORIGINAL);
        assertThat(outcome.trace().queryRewrite().rewritten()).isFalse();
        assertThat(outcome.trace().queryRewrite().fallback()).isTrue();
        assertThat(outcome.trace().queryRewrite().retrievalQuery()).isEqualTo(ORIGINAL);
        assertThat(outcome.trace().queryRewrite().retrievalQuery()).isEqualTo(reranker.receivedQuery);
    }

    // ---------- 无 history（rewrite 未触发） ----------

    @Test
    void noHistory_rerankerUsesOriginalQuestionAndTraceHasNoRewriteInfo() {
        stubRetrievalGreen();
        // Rewriter 永不该被调用；给一个"一旦被调用就会改写"的 stub 以暴露误触发
        QueryRewriteService rewriter = rewriterAlways(
                "{\"rewritten\": true, \"query\": \"不应该出现的改写\"}");
        RetrievalPipeline pipeline = pipeline(rewriter);

        RetrievalPipeline.RetrievalOutcome outcome = pipeline.execute(
                new RetrievalRequest("kb1", ORIGINAL, null, null, null, null, List.of()));

        assertThat(reranker.receivedQuery).isEqualTo(ORIGINAL);
        assertThat(outcome.trace().queryRewrite()).isNull();
        assertThat(outcome.trace().queryRewrite()).isNull();
        assertThat(outcome.trace().queryRewrite()).isNull(); // 显式：未触发即无 rewrite 信息
        assertThat(reranker.receivedQuery)
                .isEqualTo(ORIGINAL); // effective query = original
    }

    // ---------- rewrite=false 但 Rewriter 返回了不同 query 字段（防御语义） ----------

    @Test
    void rewriteFalseWithAlienQueryField_rerankerStillUsesOriginal() {
        stubRetrievalGreen();
        QueryRewriteService rewriter = rewriterAlways(
                "{\"rewritten\": false, \"query\": \"被污染的查询\"}");
        RetrievalPipeline pipeline = pipeline(rewriter);

        pipeline.execute(new RetrievalRequest("kb1", ORIGINAL, null, null, null, null, HISTORY));

        // rewritten=false 时 query 字段不被信任：pipeline 与 reranker 都用 original
        assertThat(reranker.receivedQuery).isEqualTo(ORIGINAL);
    }
}
