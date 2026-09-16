package com.rag.support;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 基础设施共享持有者（singleton container pattern，R4.1.1）。
 *
 * <p><b>解决的问题</b>：此前 8 个集成测试套件各自声明 MySQL/ES/MinIO 三容器，
 * 一次 {@code mvn test} 最多 24 个容器（≈5.5GB 内存）；ryuk 守护进程一旦因
 * 资源压力消亡，全部容器无人回收，堆积进一步拖慢后续容器启动（2026-09-16
 * 实测出现过：容器堆积 → DocumentFlowIT 启动超时 → AnswerabilityFlowIT 用例
 * 超时的连锁失败）。</p>
 *
 * <p><b>方案</b>：容器定义收敛到本类的静态字段，JVM 内惰性初始化、只启动一次；
 * {@link #acquire()} 引用计数 +1，套件 {@code @AfterAll} 调 {@link #release()}，
 * 最后一个套件负责 stop。全套运行 = 3 个容器，与套件数量无关。</p>
 *
 * <p><b>隔离边界（必须维持）</b>：容器共享，<b>数据不共享</b>——
 * <ul>
 *   <li>MySQL：Flyway 迁移幂等；套件数据以随机 UUID 主键/知识库名隔离，
 *       不互相可见；</li>
 *   <li>MinIO：各套件用<b>独立 bucket 名</b>（子类 {@link #minioBucket()}）；
 *   <li>ES：各套件直写的 chunk 均带自己的 kbId 前缀；ensureIndex 幂等共享索引；
 *       检索按 kb_id 过滤，天然隔离；</li>
 *   <li>模型替身 {@link FakeOpenAiServer} 是<b>有状态</b>的（nonStreamAnswer/
 *       failure 等），因此<b>不共享</b>：每套件自建实例（{@link #newFakeModel()}），
 *       @AfterAll 自行 stop。</li>
 * </ul>
 *
 * <p>使用方式：套件继承本类，删除自己的容器字段与 @AfterAll stop 逻辑，
 * 在 {@code @DynamicPropertySource} 中改用 {@link #mysql()} / {@link #es()} /
 * {@link #minio()}，并调用 {@link #acquire()}（@BeforeAll 时机或首次访问即可，
 * 用 registry 供应商内调用最简单）。类加载即计数，无需担心注册顺序。</p>
 */
public abstract class SharedInfraSupport {

    private static final AtomicInteger REFS = new AtomicInteger();

    private static volatile MySQLContainer<?> mysql;
    private static volatile GenericContainer<?> minio;
    private static volatile ElasticsearchContainer es;
    private static volatile boolean started;

    /** 套件注册使用（@DynamicPropertySource 静态方法内调用）：启动一次，计数 +1。 */
    public static synchronized void acquire() {
        REFS.incrementAndGet();
        if (!started) {
            mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8"))
                    .withStartupTimeout(Duration.ofMinutes(5));
            minio = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(new HttpWaitStrategy().forPort(9000)
                            .forPath("/minio/health/ready").forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(5));
            es = new ElasticsearchContainer(
                    // Docker Hub 的 elasticsearch 库镜像止步于 8.7；8.14.1 只有 elastic 官方仓库有
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.14.1"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withStartupTimeout(Duration.ofMinutes(6));
            mysql.start();
            minio.start();
            es.start();
            started = true;
        }
    }

    /** 套件 @AfterAll 调用：计数 -1，最后一个套件 stop 全部容器。 */
    public static synchronized void release() {
        if (REFS.decrementAndGet() <= 0 && started) {
            try {
                es.stop();
                minio.stop();
                mysql.stop();
            } finally {
                started = false;
                mysql = null;
                minio = null;
                es = null;
            }
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
