package com.rag.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 基础设施持有者（JVM 级 singleton，R4.1.2）。
 *
 * <p><b>演进</b>：R4.1.1 采用 acquire/release 引用计数——但引用计数只知道
 * "当前有多少套件在使用"，无法知道"后面还有没有未开始的套件"。Surefire 串行
 * 执行时 Suite A release(refs=0) 会提前 stop，Suite B 再重启，每套件一轮
 * 容器启停。R4.1.2 改为<b>真正的 JVM 生命周期 singleton</b>：</p>
 *
 * <ul>
 *   <li>静态字段 + 惰性初始化：JVM 内每个容器最多 start 一次（START_COUNT
 *       可取证，{@link #startCount()} 供生命周期验证读取）；</li>
 *   <li>停止交给 Testcontainers 内建的 JVM shutdown hook（容器注册即托管，
 *       测试 JVM 退出时 ryuk/正文钩子安全清理）——<b>套件不再手工 stop</b>，
 *       也不再有引用计数提前 stop 的窗口；</li>
 *   <li>Surefire 默认配置（forkCount=1, reuseForks=true, 无 parallel 覆盖，
 *       本仓库 pom 未改动）= 单测试 JVM 串行执行全部套件，因此"每 JVM 一次"
 *       等价于"一次 mvn test 只启动一次"。若未来引入多 fork，语义自动降级为
 *       "每 JVM 各一次"，此时 {@link #startCount()} 会如实反映，不虚报。</li>
 * </ul>
 *
 * <p><b>隔离边界（必须维持）</b>：容器共享，<b>数据不共享</b>——
 * <ul>
 *   <li>MySQL：Flyway 迁移幂等；套件数据以随机 UUID 主键/知识库名隔离，
 *       断言一律按 kbId/runId/datasetId 过滤，不依赖全表为空；</li>
 *   <li>MinIO：各套件用<b>独立 bucket 名</b>；</li>
 *   <li>ES：ensureIndex 幂等共享索引；套件数据带各自 kbId，检索按 kb_id 过滤；</li>
 *   <li>模型替身 {@link FakeOpenAiServer} 是<b>有状态</b>的（nonStreamAnswer/
 *       failure/delay/计数器），因此<b>不共享</b>：每套件经 {@link #newFakeModel()}
 *       自建实例、@AfterAll 自行 stop。</li>
 * </ul>
 */
public abstract class SharedInfraSupport {

    /** 实际发生的容器 start 次数（JVM 生命周期验证用）。 */
    private static final AtomicInteger MYSQL_STARTS = new AtomicInteger();
    private static final AtomicInteger ES_STARTS = new AtomicInteger();
    private static final AtomicInteger MINIO_STARTS = new AtomicInteger();

    private static volatile MySQLContainer<?> mysql;
    private static volatile GenericContainer<?> minio;
    private static volatile ElasticsearchContainer es;

    static {
        // JVM 退出兜底清理：singleton 容器的生命周期 = 测试 JVM 生命周期。
        // ryuk 通常会在 JVM 退出时回收（R4.1.1 版曾观测到 ryuk 消亡导致容器遗留，
        // R4.1.2 最终版实测一次 ryuk 未回收 singleton 的情况），此处钩子保证
        // 无论 ryuk 状态如何，测试 JVM 结束即 stop，不遗留容器。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ElasticsearchContainer e = es;
            GenericContainer<?> mi = minio;
            MySQLContainer<?> m = mysql;
            if (e != null) { try { e.stop(); } catch (Exception ignored) { } }
            if (mi != null) { try { mi.stop(); } catch (Exception ignored) { } }
            if (m != null) { try { m.stop(); } catch (Exception ignored) { } }
        }, "shared-infra-cleanup"));
    }

    /**
     * 惰性获取基础设施：首个调用者触发启动，此后直接复用。
     * 在套件的 {@code @DynamicPropertySource} 静态方法内调用。
     * synchronized：防理论上的并行类初始化重复启动（当前 surefire 串行下不会发生）。
     */
    public static synchronized void acquire() {
        if (mysql == null) {
            MySQLContainer<?> m = new MySQLContainer<>(DockerImageName.parse("mysql:8"))
                    .withStartupTimeout(Duration.ofMinutes(5));
            m.start();
            MYSQL_STARTS.incrementAndGet();
            mysql = m;
        }
        if (minio == null) {
            GenericContainer<?> mi = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(new HttpWaitStrategy().forPort(9000)
                            .forPath("/minio/health/ready").forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(5));
            mi.start();
            MINIO_STARTS.incrementAndGet();
            minio = mi;
        }
        if (es == null) {
            ElasticsearchContainer e = new ElasticsearchContainer(
                    // Docker Hub 的 elasticsearch 库镜像止步于 8.7；8.14.1 只有 elastic 官方仓库有
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.14.1"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withStartupTimeout(Duration.ofMinutes(6));
            e.start();
            ES_STARTS.incrementAndGet();
            es = e;
        }
    }

    public static MySQLContainer<?> mysql() {
        return mysql;
    }

    public static GenericContainer<?> minio() {
        return minio;
    }

    public static ElasticsearchContainer es() {
        return es;
    }

    /** 各容器在当前 JVM 内的实际启动次数（应恒为 1；多 fork 时每 JVM 各 1）。 */
    public static int mysqlStartCount() {
        return MYSQL_STARTS.get();
    }

    public static int esStartCount() {
        return ES_STARTS.get();
    }

    public static int minioStartCount() {
        return MINIO_STARTS.get();
    }

    /** 每套件独立模型替身（有状态，不共享）；套件 @AfterAll 自行 stop。 */
    public static FakeOpenAiServer newFakeModel() {
        try {
            FakeOpenAiServer fake = new FakeOpenAiServer();
            fake.start();
            return fake;
        } catch (Exception e) {
            throw new IllegalStateException("FakeOpenAiServer 启动失败", e);
        }
    }
}
