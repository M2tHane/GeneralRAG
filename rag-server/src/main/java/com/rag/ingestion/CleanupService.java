package com.rag.ingestion;

import java.util.List;
import java.util.Map;

import com.rag.domain.entity.CleanupTaskEntity;
import com.rag.domain.enums.CleanupStatus;
import com.rag.domain.enums.StoreType;
import com.rag.storage.es.EsChunkIndex;
import com.rag.storage.minio.ObjectStore;
import com.rag.storage.repository.CleanupTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 跨存储清理补偿执行器（路线 §3.3）。
 *
 * <p>MySQL 主档删除先行收口检索边界；ES 分块与 MinIO 对象由本服务异步清理：
 * 每 30s 扫描 PENDING/FAILED 任务，成功 → DONE，异常 → FAILED + last_error + attempts+1
 * （无上限重试，持续记录，可通过 actuator 指标观察积压）。</p>
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final CleanupTaskRepository cleanupTaskRepository;
    private final EsChunkIndex esChunkIndex;
    private final ObjectStore objectStore;

    public CleanupService(CleanupTaskRepository cleanupTaskRepository,
                          EsChunkIndex esChunkIndex,
                          ObjectStore objectStore) {
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.esChunkIndex = esChunkIndex;
        this.objectStore = objectStore;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void processPending() {
        List<CleanupTaskEntity> tasks = cleanupTaskRepository
                .findByStatusInOrderByUpdatedAtAsc(List.of(CleanupStatus.PENDING, CleanupStatus.FAILED));
        if (tasks.isEmpty()) {
            return;
        }
        log.info("清理补偿：扫描到 {} 个待处理任务", tasks.size());
        for (CleanupTaskEntity task : tasks) {
            processOne(task);
        }
    }

    /** 供删除链路（KB/文档删除）受理后立即触发一轮处理时复用。 */
    public void processOne(CleanupTaskEntity task) {
        try {
            Map<String, Object> payload = task.getPayload();
            String docId = payload == null ? null : (String) payload.get("docId");
            String kbId = payload == null ? null : (String) payload.get("kbId");
            if (task.getStore() == StoreType.ELASTICSEARCH) {
                if (docId != null) {
                    esChunkIndex.deleteByDoc(docId);
                } else if (kbId != null) {
                    esChunkIndex.deleteByKb(kbId);
                }
            } else if (task.getStore() == StoreType.MINIO) {
                if (docId != null && kbId != null) {
                    objectStore.removeDoc(kbId, docId);
                } else if (kbId != null) {
                    objectStore.removeKb(kbId);
                }
            } else {
                throw new IllegalStateException("未知的清理存储类型：" + task.getStore());
            }
            task.setStatus(CleanupStatus.DONE);
            task.setLastError(null);
            cleanupTaskRepository.save(task);
            log.info("清理任务完成（taskId={}, store={}, scope={}）", task.getId(), task.getStore(), task.getScope());
        } catch (Exception e) {
            // M1：不能用 bulk incrementAttempts 后再 save(detached)——旧值会覆盖 DB 新值，
            // attempts 永远不增长。改为内存 +1 后一并 save（单写路径，无并发覆盖问题：
            // 并发重复执行由幂等的删除操作兜底，m1 认领优化不改变正确性）。
            task.setAttempts(task.getAttempts() + 1);
            task.setLastError(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 512));
            task.setStatus(CleanupStatus.FAILED);
            cleanupTaskRepository.save(task);
            log.warn("清理任务失败，待下轮重试（taskId={}, attempts+1）：{}",
                    task.getId(), task.getLastError());
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
