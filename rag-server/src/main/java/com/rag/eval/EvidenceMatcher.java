package com.rag.eval;

import java.util.List;
import java.util.Map;

import com.rag.storage.es.EsHit;

/**
 * 证据命中判定（R2-E1 分块级；R3-P1 支持 contentHash 锚点）——
 * <b>唯一实现</b>，被执行器与视图服务共用。
 *
 * <p>抽成共享组件的原因：判定口径必须与"运行详情里展示的排名"完全一致，
 * 否则用户看到的名次和指标分子的口径会不一致（第一轮正是判定口径与展示口径
 * 各写一份而漂移）。</p>
 *
 * <p><b>为什么是分块级</b>：第一轮用 evidence 的 {@code docName} 或 {@code titlePath}
 * 相等来判定，而数据集里的 titlePath 从未等于真实分块路径，命中实际全部由
 * {@code docName} 承担 → 退化为文档级，4 篇文档下指标饱和、失去区分度。</p>
 *
 * <p><b>锚点优先级</b>：{@code contentHash} → {@code chunkId} → {@code titlePath}
 * → {@code anchorPath}，四者都是分块级锚点，精确相等。
 * {@code contentHash}（R3-P1）为分块正文的规范化 SHA-256 前 16 hex（见
 * {@link #contentHashOf(String)}）——与文件名/分块策略解耦的稳定锚点：文档改名、
 * 重入库导致 chunkId 变化后仍可判定命中；数据集生成时按真实分块内容计算。</p>
 *
 * <p>刻意<b>不再回退</b>到 docName 相等：证据缺少分块级锚点时该条记为未命中，
 * 并会在报告里体现为"数据集锚点不足"，而不是给出虚高的文档级满分。</p>
 */
public final class EvidenceMatcher {

    private EvidenceMatcher() {
    }

    /**
     * 分块正文的稳定哈希锚点（R3-P1）：规范化（去首尾空白 + 内部空白折叠为单空格）
     * 后取 SHA-256 前 16 hex。数据集生成与检索判定必须用同一函数——本类是唯一入口。
     *
     * @param content 分块正文
     * @return 16 位十六进制小写串；content 为空返回 null（空内容不构成锚点）
     */
    public static String contentHashOf(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String normalized = content.strip().replaceAll("\\s+", " ");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 参考证据在命中集中的首个匹配位次。
     *
     * @param evidence    单条参考证据（contentHash / chunkId / titlePath / anchorPath
     *                    任一可作为锚点）
     * @param orderedHits 本次命中（按最终排名升序；含分块正文，可现算哈希）
     * @return 1 起的位次；未命中返回 0
     */
    public static int firstMatchRank(Map<String, Object> evidence, List<EsHit> orderedHits) {
        if (evidence == null || orderedHits == null || orderedHits.isEmpty()) {
            return 0;
        }
        String evidenceContentHash = str(evidence.get("contentHash"));
        String evidenceChunkId = str(evidence.get("chunkId"));
        String evidenceTitlePath = str(evidence.get("titlePath"));
        String evidenceAnchorPath = str(evidence.get("anchorPath"));
        if (evidenceContentHash == null && evidenceChunkId == null
                && evidenceTitlePath == null && evidenceAnchorPath == null) {
            return 0;
        }
        int rank = 1;
        for (EsHit chunk : orderedHits) {
            if (chunk == null) {
                rank++;
                continue;
            }
            if (evidenceContentHash != null
                    && evidenceContentHash.equals(contentHashOf(chunk.content()))) {
                return rank;
            }
            if (evidenceChunkId != null && evidenceChunkId.equals(chunk.chunkId())) {
                return rank;
            }
            if (evidenceTitlePath != null && evidenceTitlePath.equals(chunk.titlePath())) {
                return rank;
            }
            if (evidenceAnchorPath != null && evidenceAnchorPath.equals(chunk.titlePath())) {
                return rank;
            }
            rank++;
        }
        return 0;
    }

    /**
     * 一组证据的首个命中位次。
     *
     * @return 命中位次（1 起）；全部未命中返回 0
     */
    public static int firstMatchRank(List<Map<String, Object>> evidenceList,
                                     List<EsHit> orderedHits) {
        if (evidenceList == null || evidenceList.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (Map<String, Object> evidence : evidenceList) {
            int rank = firstMatchRank(evidence, orderedHits);
            if (rank > 0 && (best == 0 || rank < best)) {
                best = rank;
            }
        }
        return best;
    }

    /**
     * 一组证据的首个命中位次的显式别名。
     *
     * <p>存在原因：{@code firstMatchRank} 同时有 (Map, List) 与 (List, List) 两个重载，
     * 调用方传 {@code List.of()} 时 Java 会解析到 Map 版本（Map 不是 List 但擦除后同为 Object），
     * 造成误用。显式命名可消除歧义。</p>
     */
    public static int firstMatchRankOfAllAsList(List<Map<String, Object>> evidenceList,
                                                List<EsHit> orderedHits) {
        return firstMatchRank(evidenceList, orderedHits);
    }

    /** 是否命中（任一证据匹配即命中）。 */
    public static boolean isHit(List<Map<String, Object>> evidenceList, List<EsHit> orderedHits) {
        return firstMatchRank(evidenceList, orderedHits) > 0;
    }

    /**
     * 证据中是否存在可用于分块级判定的锚点。
     * 用于识别"数据集锚点不足"（这类题目的 hit=false 不代表检索失败）。
     */
    public static boolean hasChunkLevelAnchor(Map<String, Object> evidence) {
        return evidence != null
                && (str(evidence.get("contentHash")) != null
                || str(evidence.get("chunkId")) != null
                || str(evidence.get("titlePath")) != null
                || str(evidence.get("anchorPath")) != null);
    }

    /**
     * 候选的最小标识（contentHash + chunkId + titlePath）——用于对<b>已落库的
     * retrieved 快照</b>复算证据位次，供运行详情展示"正确答案排在第几"，无需重新检索。
     * 快照未存 content（旧数据）时 contentHash 为 null，仅支持 chunkId/titlePath 匹配。
     *
     * @param contentHash 候选分块内容哈希（可空）
     * @param chunkId     分块标识
     * @param titlePath   标题路径
     */
    public record CandidateRef(String contentHash, String chunkId, String titlePath) {

        /** 旧调用兼容：无内容哈希（快照未存 content）。 */
        public static CandidateRef of(String chunkId, String titlePath) {
            return new CandidateRef(null, chunkId, titlePath);
        }
    }

    /**
     * 证据在候选序列中的首个匹配位次（对已落库快照复算）。
     *
     * @return 1 起的位次；未命中返回 0
     */
    public static int firstMatchRankInSnapshot(Map<String, Object> evidence,
                                               List<CandidateRef> candidates) {
        if (evidence == null || candidates == null || candidates.isEmpty()) {
            return 0;
        }
        String evidenceContentHash = str(evidence.get("contentHash"));
        String evidenceChunkId = str(evidence.get("chunkId"));
        String evidenceTitlePath = str(evidence.get("titlePath"));
        String evidenceAnchorPath = str(evidence.get("anchorPath"));
        if (evidenceContentHash == null && evidenceChunkId == null
                && evidenceTitlePath == null && evidenceAnchorPath == null) {
            return 0;
        }
        int rank = 1;
        for (CandidateRef c : candidates) {
            if (c == null) {
                rank++;
                continue;
            }
            if (evidenceContentHash != null && c.contentHash() != null
                    && evidenceContentHash.equals(c.contentHash())) {
                return rank;
            }
            if (evidenceChunkId != null && evidenceChunkId.equals(c.chunkId())) {
                return rank;
            }
            if (evidenceTitlePath != null && evidenceTitlePath.equals(c.titlePath())) {
                return rank;
            }
            if (evidenceAnchorPath != null && evidenceAnchorPath.equals(c.titlePath())) {
                return rank;
            }
            rank++;
        }
        return 0;
    }

    /** 一组证据在候选序列中的首个匹配位次（1 起；全部未命中返回 0）。 */
    public static int firstMatchRankOfAllInSnapshot(List<Map<String, Object>> evidenceList,
                                                    List<CandidateRef> candidates) {
        if (evidenceList == null || evidenceList.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (Map<String, Object> evidence : evidenceList) {
            int rank = firstMatchRankInSnapshot(evidence, candidates);
            if (rank > 0 && (best == 0 || rank < best)) {
                best = rank;
            }
        }
        return best;
    }

    private static String str(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value);
    }
}
