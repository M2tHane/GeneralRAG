package com.rag;

import com.rag.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通用知识问答 RAG 服务入口。
 *
 * <p>{@code @EnableScheduling} 是 Task 3 CleanupService 的 @Scheduled 跨存储清理任务
 * （30s 扫描 cleanup_task 表）的前提；缺失时清理任务会静默不运行（KB 删除/文档删除
 * 在 ES 与 MinIO 侧的残留将永远无法收敛），不可移除。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RagProperties.class)
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
