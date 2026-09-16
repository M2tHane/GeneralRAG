package com.rag.storage.es;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.rag.config.RagProperties;
import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 分块 ES 索引（路线 §4.2）：索引 {@link #INDEX_NAME} + 别名 {@link #ALIAS_NAME}，
 * mapping v1（v1 后缀为未来 reindex 预留）。分块正文唯一存于 ES，MySQL 只存聚合。
 *
 * <p>关键行为：</p>
 * <ul>
 *   <li>{@link #ensureIndex()}：不存在则按当前 mapping 创建；创建/打开前探测配置的
 *       content 分析器可用性（_analyze），不可用即启动失败并给出两个处置选项；
 *   <li>{@link #rebuildChunks}：先 delete_by_query(doc_id) 再 bulk 写入——「重建分块」
 *       语义天然幂等（路线 §3.1），重复执行不产生重复分块，是中断恢复的前提；
 *   <li>{@link #knnSearch}：kNN + knowledge_base_id term filter（QA-1 跨库隔离）；
 *       min_score 由上层应用侧过滤（路线 §4.2 取舍）。</li>
 * </ul>
 *
 * <p>ElasticsearchClient 为 Spring Boot 自动装配（spring.elasticsearch.uris，
 * Jackson JsonpMapper）。本类不自动在启动时建索引，由后续任务的应用级启动钩子
 * 显式调用 {@link #ensureIndex()}（见交付报告遗留事项）。</p>
 */
@Component
public class EsChunkIndex {

    private static final Logger log = LoggerFactory.getLogger(EsChunkIndex.class);

    public static final String INDEX_NAME = "rag_chunks_v1";
    public static final String ALIAS_NAME = "rag_chunks";

    private final ElasticsearchClient client;
    private final String contentAnalyzer;
    private final int vectorDims;

    public EsChunkIndex(ElasticsearchClient client, RagProperties ragProperties) {
        this.client = client;
        this.contentAnalyzer = ragProperties.getElasticsearch().getContentAnalyzer();
        this.vectorDims = ragProperties.getModels().getEmbedding().getDimensions();
    }

    /** 索引不存在则创建；存在则做 retrieval_content mapping 迁移检查；每次调用都先探测分析器可用性。 */
    public void ensureIndex() {
        try {
            BooleanResponse exists = client.indices().exists(e -> e.index(INDEX_NAME));
            probeAnalyzer(exists.value());
            if (!exists.value()) {
                createIndex();
                log.info("已创建 ES 索引 {}（别名 {}，analyzer={}，vector dims={}）",
                        INDEX_NAME, ALIAS_NAME, contentAnalyzer, vectorDims);
            } else {
                migrateRetrievalContentMapping();
            }
        } catch (IOException e) {
            throw esUnavailable(e);
        }
    }

    /**
     * R5.1：已有索引的 retrieval_content mapping 兼容迁移（非破坏）。
     *
     * <p>背景：R5-A 之前的旧索引没有该字段；若索引先于本修复遇到写入，
     * 动态 mapping 会以默认 analyzer（standard）建字段，与 content 的
     * {@code contentAnalyzer}（如 ik_max_word）不一致。</p>
     *
     * <ul>
     *   <li>字段不存在 → PUT mapping 补齐（type=text + 当前 analyzer）；
     *       ES 支持对已有索引新增 text 字段，旧文档该字段为空（读侧回退 content）；</li>
     *   <li>字段存在且 analyzer 一致 → 正常继续；</li>
     *   <li>字段存在但 analyzer 不一致 → <b>不自动删索引/重建数据</b>，
     *       明确报错要求人工 reindex。</li>
     * </ul>
     */
    private void migrateRetrievalContentMapping() throws IOException {
        GetMappingResponse mapping = client.indices()
                .getMapping(g -> g.index(INDEX_NAME));
        var properties = mapping.result().get(INDEX_NAME).mappings().properties();
        Property existing = properties.get("retrieval_content");
        if (existing == null) {
            client.indices().putMapping(p -> p.index(INDEX_NAME)
                    .properties("retrieval_content",
                            pr -> pr.text(t -> t.analyzer(contentAnalyzer))));
            log.info("已为既有索引 {} 补齐 retrieval_content mapping（analyzer={}）；"
                    + "旧 chunk 该字段为空，检索回退 content，重新 ingest 后生效",
                    INDEX_NAME, contentAnalyzer);
            return;
        }
        String actualAnalyzer = existing.isText() ? existing.text().analyzer() : null;
        if (contentAnalyzer.equals(actualAnalyzer)) {
            return; // 一致：正常继续
        }
        throw new DomainException(ErrorCode.INTERNAL_ERROR,
                "Elasticsearch 索引 " + INDEX_NAME + " 的 retrieval_content analyzer 为 '"
                        + (actualAnalyzer == null ? "default" : actualAnalyzer)
                        + "'，与配置的 '" + contentAnalyzer + "' 不一致。"
                        + "text 字段 analyzer 无法就地修改——请人工处理：删除该字段不可行，"
                        + "需 reindex（新建索引→_reindex→切别名）或清空后重新 ingest。"
                        + "本系统拒绝以错误的分词器继续检索。");
    }

    /**
     * 启动前探测内容分析器存在性：ES 未安装 analysis-ik 时此处立即失败，
     * 提示两个处置选项（安装 IK，或改 rag.elasticsearch.content-analyzer=standard）。
     */
    private void probeAnalyzer(boolean indexExists) {
        try {
            client.indices().analyze(a -> {
                if (indexExists) {
                    a.index(INDEX_NAME);
                }
                return a.analyzer(contentAnalyzer).text("知识库分块内容分析器探测");
            });
        } catch (ElasticsearchException | IOException e) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR,
                    "内容分析器 '" + contentAnalyzer + "' 在 Elasticsearch 中不可用（"
                            + e.getMessage() + "）。处置：为 ES 安装 analysis-ik 插件，"
                            + "或将配置 rag.elasticsearch.content-analyzer 改为 standard。");
        }
    }

    private void createIndex() throws IOException {
        client.indices().create(c -> c.index(INDEX_NAME)
                .aliases(ALIAS_NAME, a -> a)
                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("doc_id", p -> p.keyword(k -> k))
                        .properties("knowledge_base_id", p -> p.keyword(k -> k))
                        .properties("title_path", p -> p.keyword(k -> k))
                        .properties("page", p -> p.integer(i -> i))
                        .properties("seq", p -> p.integer(i -> i))
                        .properties("char_count", p -> p.integer(i -> i))
                        .properties("content", p -> p.text(t -> t.analyzer(contentAnalyzer)))
                        // R5-A：检索增强表示（与正文同 analyzer）；旧文档无此字段时
                        // BM25 回退 content 字段（见 bm25Search）
                        .properties("retrieval_content", p -> p.text(t -> t.analyzer(contentAnalyzer)))
                        .properties("vector", p -> p.denseVector(d -> d
                                .dims(vectorDims)
                                .index(true)
                                .similarity("cosine")))));
    }

    /**
     * 幂等重建某文档的全部分块：先按 doc_id 删除旧块，再整批写入。
     * CHUNKING/EMBEDDING/INDEXING 三阶段在重试时合并走本方法（路线 §3.1）。
     *
     * @return 写入的分块数（0 块为合法：空文档不报错）
     */
    public int rebuildChunks(String docId, String kbId, List<ChunkDoc> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("分块数与向量数不一致：chunks=" + chunks.size()
                    + ", vectors=" + vectors.size() + ", docId=" + docId);
        }
        // 先整体校验维度再动 ES：坏入参不应触发 delete_by_query
        for (int i = 0; i < chunks.size(); i++) {
            requireDims(docId, chunks.get(i), vectors.get(i));
        }
        deleteByDoc(docId);
        if (chunks.isEmpty()) {
            return 0;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDoc chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            bulk.operations(op -> op.index(idx -> idx
                    .index(INDEX_NAME)
                    .id(chunk.id())
                    .document(toSourceMap(docId, kbId, chunk, vector))));
        }
        try {
            BulkResponse response = client.bulk(bulk.build());
            if (response.errors()) {
                String detail = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.id() + ": " + item.error().reason())
                        .collect(Collectors.joining("; "));
                throw new DomainException(ErrorCode.INTERNAL_ERROR,
                        "分块批量写入 ES 失败（docId=" + docId + "）：" + detail);
            }
            // 立即可见：重试判定与集成测试都依赖写后可读
            client.indices().refresh(r -> r.index(INDEX_NAME));
            return chunks.size();
        } catch (IOException e) {
            throw esUnavailable(e);
        }
    }

    private void requireDims(String docId, ChunkDoc chunk, float[] vector) {
        if (vector == null || vector.length != vectorDims) {
            throw new IllegalArgumentException("向量维度与配置不一致：期望 " + vectorDims
                    + "，实际 " + (vector == null ? "null" : vector.length)
                    + "（chunkId=" + chunk.id() + ", docId=" + docId + "）");
        }
    }

    /** kNN 检索：term filter knowledge_base_id 实现跨库隔离（QA-1）。 */
    public List<EsHit> knnSearch(String kbId, float[] queryVector, int topK, int numCandidates) {
        int candidates = Math.max(numCandidates, topK);
        try {
            SearchResponse<Map> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .knn(k -> k.field("vector")
                                    .queryVector(toFloatList(queryVector))
                                    .k(topK)
                                    .numCandidates(candidates)
                                    .filter(f -> f.term(t -> t.field("knowledge_base_id").value(kbId))))
                            .size(topK),
                    Map.class);
            List<EsHit> hits = new ArrayList<>(response.hits().hits().size());
            for (Hit<Map> hit : response.hits().hits()) {
                hits.add(toEsHit(hit));
            }
            return hits;
        } catch (IOException e) {
            throw esUnavailable(e);
        }
    }

    /**
     * BM25 词法检索（R2-H1）：复用已有 {@code content} text mapping 与 IK 分析器，
     * <b>不改 mapping、不 reindex</b>——标题文本本就在分块正文内（StructureChunker
     * 把标题行保留为块首行），因此标题词天然可被 BM25 命中。
     *
     * <p>term filter {@code knowledge_base_id} 保持跨库隔离（QA-1），与 kNN 通道一致。
     * 返回按 ES _score 降序（BM25 原始分，无上界，不可跨查询比较）。</p>
     */
    public List<EsHit> bm25Search(String kbId, String question, int topK) {
        try {
            SearchResponse<Map> response = client.search(s -> s
                            .index(INDEX_NAME)
                            .query(q -> q.bool(b -> b
                                    // R5.1：真正的 fallback 语义——新 chunk 只吃
                                    // retrieval_content 的 BM25 分，旧 chunk（无该字段）
                                    // 回退 content；同一 chunk 不会两份正文叠加
                                    .must(m -> m.bool(inner -> inner
                                            .should(sh -> sh.bool(newChunk -> newChunk
                                                    .filter(fv -> fv.exists(e -> e.field("retrieval_content")))
                                                    .must(mm -> mm.match(mt -> mt.field("retrieval_content").query(question)))))
                                            .should(sh -> sh.bool(oldChunk -> oldChunk
                                                    .mustNot(fv -> fv.exists(e -> e.field("retrieval_content")))
                                                    .must(mm -> mm.match(mt -> mt.field("content").query(question)))))
                                            .minimumShouldMatch("1")))
                                    .filter(f -> f.term(t -> t.field("knowledge_base_id").value(kbId)))))
                            .size(Math.max(1, topK)),
                    Map.class);
            List<EsHit> hits = new ArrayList<>(response.hits().hits().size());
            for (Hit<Map> hit : response.hits().hits()) {
                hits.add(toEsHit(hit));
            }
            return hits;
        } catch (IOException e) {
            throw esUnavailable(e);
        }
    }

    /** 删除某文档全部分块（文档删除 / 重建前清理）。返回删除数。 */    public long deleteByDoc(String docId) {
        return deleteByTerm("doc_id", docId);
    }

    /** 删除某知识库全部分块（KB 删除补偿清理）。返回删除数。 */
    public long deleteByKb(String kbId) {
        return deleteByTerm("knowledge_base_id", kbId);
    }

    private long deleteByTerm(String field, String value) {
        try {
            DeleteByQueryResponse response = client.deleteByQuery(d -> d
                    .index(INDEX_NAME)
                    .query(q -> q.term(t -> t.field(field).value(value)))
                    .conflicts(Conflicts.Proceed)
                    .refresh(true));
            return response.deleted();
        } catch (IOException e) {
            throw esUnavailable(e);
        }
    }

    /** ES _source 文档结构（字段名与 §4.2 mapping 一致）。包内可见供单测。 */
    static Map<String, Object> toSourceMap(String docId, String kbId, ChunkDoc chunk, float[] vector) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", chunk.id());
        source.put("doc_id", docId);
        source.put("knowledge_base_id", kbId);
        source.put("title_path", chunk.titlePath());
        if (chunk.page() != null) {
            source.put("page", chunk.page());
        }
        source.put("seq", chunk.seq());
        source.put("char_count", chunk.charCount());
        source.put("content", chunk.content());
        // R5-A：检索表示（null = 旧数据/未启用双层，读侧回退 content）
        if (chunk.retrievalContent() != null) {
            source.put("retrieval_content", chunk.retrievalContent());
        }
        source.put("vector", toFloatList(vector));
        return source;
    }

    /** Hit<Map> → EsHit 映射（score 缺失按 0 处理）。包内可见供单测。 */
    static EsHit toEsHit(Hit<Map> hit) {
        @SuppressWarnings("unchecked")
        Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
        Integer page = (Integer) source.get("page");
        Integer seq = (Integer) source.get("seq");
        Integer charCount = (Integer) source.get("char_count");
        Object retrievalContent = source.get("retrieval_content");
        return new EsHit(
                hit.id(),
                (String) source.get("doc_id"),
                (String) source.get("title_path"),
                page,
                seq == null ? 0 : seq,
                charCount == null ? 0 : charCount,
                (String) source.get("content"),
                retrievalContent instanceof String rc ? rc : null,
                hit.score() == null ? 0.0f : hit.score());
    }

    private static List<Float> toFloatList(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("向量不能为空");
        }
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private DomainException esUnavailable(IOException e) {
        log.error("Elasticsearch 操作失败", e);
        return new DomainException(ErrorCode.INTERNAL_ERROR,
                "Elasticsearch 操作失败：" + e.getMessage() + "（index=" + INDEX_NAME + "）");
    }

    @Override
    public String toString() {
        return "EsChunkIndex{index=" + INDEX_NAME + ", alias=" + ALIAS_NAME
                + ", analyzer=" + contentAnalyzer + ", dims=" + vectorDims + "}";
    }
}
