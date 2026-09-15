package com.rag.config;

import com.rag.domain.exception.DomainException;
import com.rag.storage.es.EsChunkIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动期初始化（路线 §4.2）：ES 分块索引不存在则按当前 mapping 创建
 * （含分析器可用性探测，探测失败抛出带两种处置提示的异常阻止启动）。
 * 幂等：索引已存在时无操作。
 */
@Component
public class StartupInitializer {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final EsChunkIndex esChunkIndex;

    public StartupInitializer(EsChunkIndex esChunkIndex) {
        this.esChunkIndex = esChunkIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            esChunkIndex.ensureIndex();
            log.info("ES 分块索引就绪");
        } catch (DomainException e) {
            // 索引/分析器问题必须阻止应用带着坏状态运行（路线 §4.2）
            throw new IllegalStateException("ES 索引初始化失败：" + e.getMessage(), e);
        }
    }
}
