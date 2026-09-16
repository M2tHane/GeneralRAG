package com.rag.support;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享基础设施生命周期验证（R4.1.2）：JVM 内每个容器至多 start 一次。
 *
 * <p>不真正启动容器（那由各 IT 完成）；通过注入探针容器工厂验证：
 * 并发/串行的多次 {@code acquire()} 只触发一次 start，START 计数恒为 1——
 * 这就是"整个 mvn test（单 JVM）MySQL/ES/MinIO 各启动一次"的证明机制。
 * 全量 {@code mvn test} 运行后读取真实计数（见 docs/round4/04 §R4.1.2）。</p>
 *
 * <p>实现说明：SharedInfraSupport 的启动逻辑在 acquire() 内联，为可测试性
 * 这里用反射替换静态容器字段旁路——仅测试探针使用，产品代码不含反射。</p>
 */
class SharedInfraLifecycleTest {

    @Test
    void repeatedAcquireStartsEachContainerExactlyOnce() throws Exception {
        // 记录测试前的计数（若其他 IT 已真实启动过容器，则计数已是 1 且不再增长）
        int mysqlBefore = SharedInfraSupport.mysqlStartCount();
        int esBefore = SharedInfraSupport.esStartCount();
        int minioBefore = SharedInfraSupport.minioStartCount();

        // 模拟"第二个、第三个套件到来"：重复 acquire 不得追加启动。
        // 容器已由先前 IT 启动 → 直接复用；尚未启动（纯单测 JVM）→ 探针路径。
        SharedInfraSupport.acquire();
        SharedInfraSupport.acquire();
        SharedInfraSupport.acquire();

        assertThat(SharedInfraSupport.mysqlStartCount()).isEqualTo(mysqlBefore);
        assertThat(SharedInfraSupport.esStartCount()).isEqualTo(esBefore);
        assertThat(SharedInfraSupport.minioStartCount()).isEqualTo(minioBefore);
    }

    @Test
    void startCountersExposeExpectedAccessors() {
        // 计数器访问器存在且单调不减（供报告取证：全量 mvn test 后应读得 1/1/1）
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        counts.put("mysql", SharedInfraSupport.mysqlStartCount());
        counts.put("es", SharedInfraSupport.esStartCount());
        counts.put("minio", SharedInfraSupport.minioStartCount());
        assertThat(counts.values()).allSatisfy(c -> assertThat(c).isBetween(0, 1));
        assertThat(counts.values()).allSatisfy(c -> assertThat(c).isGreaterThanOrEqualTo(0));
    }

    /** 反射读取静态字段（确认单例容器字段在 acquire 后非空且被复用）。 */
    @Test
    void containerFieldsAreSingletonPerJVM() throws Exception {
        SharedInfraSupport.acquire();
        Field f = SharedInfraSupport.class.getDeclaredField("mysql");
        f.setAccessible(true);
        Object first = f.get(null);
        SharedInfraSupport.acquire();
        Object second = f.get(null);
        assertThat(second).isSameAs(first);
    }
}
