package com.rag.ingestion.pipeline;

import java.util.List;

import com.rag.domain.entity.DocumentEntity;
import com.rag.domain.entity.IngestionTaskEntity;
import com.rag.ingestion.chunk.ChunkDraft;
import com.rag.ingestion.parse.ParsedDocument;

/**
 * 单次执行内跨阶段传递的上下文。
 *
 * <p>阶段产物同时有确定性落点（parsed.txt / ES），重启后的续跑不从本对象恢复，
 * 而是按阶段证据重建（见 IngestionTaskManager 各阶段执行逻辑）。</p>
 */
public final class StageContext {

    public final DocumentEntity document;
    public final IngestionTaskEntity task;

    /** PARSING 产物。 */
    public ParsedDocument parsed;

    /** PARSING 阶段发现 parsed.txt 已存在（重试续跑证据）→ CLEANING 一并跳过。 */
    public boolean parsingAlreadyDone;

    /** CLEANING 产物（parsed.txt 落盘内容；CHUNKING 优先复用，免去一次 MinIO 读）。 */
    public String cleanedText;

    /** CHUNKING 产物。 */
    public List<ChunkDraft> drafts;

    /** EMBEDDING 产物（与 drafts 一一对应）。 */
    public List<float[]> vectors;

    public StageContext(DocumentEntity document, IngestionTaskEntity task) {
        this.document = document;
        this.task = task;
    }
}
