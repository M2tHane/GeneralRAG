package com.rag.retrieval;

import com.rag.retrieval.model.RetrievalHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * retrieval 包装配：未配置重排服务时提供恒等实现。
 *
 * <p>真实的 {@link DashScopeReranker} 在 {@code rag.retrieval.rerank.base-url} 非空时
 * 装配（@ConditionalOnProperty），此时本 Bean 因 {@code @ConditionalOnMissingBean}
 * 让位。VECTOR / HYBRID 模式本就不调用重排，恒等实现即可。</p>
 */
@Configuration
public class RetrievalConfig {

    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    public Reranker noOpReranker() {
        return (kbId, question, hits) -> Reranker.RerankOutcome.applied(hits);
    }
}
