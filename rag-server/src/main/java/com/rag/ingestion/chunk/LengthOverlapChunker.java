package com.rag.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;

import com.rag.domain.enums.ChunkStrategy;
import com.rag.ingestion.parse.ParsedDocument;
import org.springframework.stereotype.Component;

/**
 * 固定长度滑窗分块：按 maxLength 切分，相邻块重叠 overlap 字符（路线 Task 3）。
 * titlePath = 文档名；page = null（长度切分不感知页/标题结构）。
 */
@Component
public class LengthOverlapChunker implements Chunker {

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.LENGTH_OVERLAP;
    }

    @Override
    public List<ChunkDraft> chunk(ParsedDocument doc, ChunkingConfig cfg, String docId) {
        String text = doc.text() == null ? "" : doc.text();
        if (text.isBlank()) {
            return List.of();
        }
        int step = cfg.maxLength() - cfg.overlap();
        List<ChunkDraft> drafts = new ArrayList<>();
        int seq = 0;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + cfg.maxLength(), text.length());
            String chunkText = text.substring(start, end);
            drafts.add(new ChunkDraft(chunkId(docId, seq), seq, doc.documentName(), null,
                    chunkText.length(), chunkText));
            seq++;
            if (end == text.length()) {
                break;
            }
        }
        return drafts;
    }

    /** chunkId 统一格式：{docId}-c%04d（Chunker 实现共用，可重放）。 */
    static String chunkId(String docId, int seq) {
        return docId + String.format("-c%04d", seq);
    }
}
