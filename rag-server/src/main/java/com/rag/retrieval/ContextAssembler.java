package com.rag.retrieval;

import java.util.Comparator;
import java.util.List;

import com.rag.config.RagProperties;
import com.rag.retrieval.model.Context;
import com.rag.retrieval.model.RetrievalHit;
import org.springframework.stereotype.Component;

/**
 * 上下文组装（docs/03-技术路线.md §3.2 步骤 5）：通过阈值的命中按分数序
 * 拼接为「【文档 N】{titlePath}\n{content}」，超过 max-context-chars 截断。
 */
@Component
public class ContextAssembler {

    private final int maxContextChars;

    @org.springframework.beans.factory.annotation.Autowired
    public ContextAssembler(RagProperties ragProperties) {
        this.maxContextChars = ragProperties.getRetrieval().getMaxContextChars();
    }

    /** 测试用构造。 */
    ContextAssembler(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    /**
     * @param passedHits 已通过 minScore 的命中（分数降序；防御性再排序）
     * @return 上下文全文 + 元信息；无命中时 text=「（无相关资料）」、chunkIds=[]
     */
    public Context assemble(List<RetrievalHit> passedHits) {
        List<RetrievalHit> ordered = passedHits == null ? List.of()
                : passedHits.stream()
                        .sorted(Comparator.comparingDouble(RetrievalHit::score).reversed())
                        .toList();
        if (ordered.isEmpty()) {
            return Context.empty();
        }

        StringBuilder text = new StringBuilder();
        List<String> chunkIds = new java.util.ArrayList<>(ordered.size());
        boolean truncated = false;
        int index = 0;
        for (RetrievalHit hit : ordered) {
            String section = "【文档 " + (++index) + "】" + safe(hit.chunk().titlePath())
                    + "\n" + safe(hit.chunk().content()) + "\n\n";
            int remaining = maxContextChars - text.length();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (section.length() > remaining) {
                text.append(section, 0, remaining);
                chunkIds.add(hit.chunk().chunkId());
                truncated = true;
                break;
            }
            text.append(section);
            chunkIds.add(hit.chunk().chunkId());
        }
        String result = text.toString().stripTrailing();
        return new Context(result, result.length(), List.copyOf(chunkIds), truncated);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
