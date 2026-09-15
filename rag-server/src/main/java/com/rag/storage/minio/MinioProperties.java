package com.rag.storage.minio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 连接配置（application.yaml 已有 minio.* 键位，Task 1 产出）：
 *
 * <pre>
 * minio:
 *   endpoint:   ${MINIO_ENDPOINT:http://localhost:9000}
 *   access-key: ${MINIO_ACCESS_KEY:minioadmin}
 *   secret-key: ${MINIO_SECRET_KEY:minioadmin}
 *   bucket:     rag
 * </pre>
 *
 * <p>本类注册方式：{@link MinioConfig} 内 {@code @Bean @ConfigurationProperties} 方法绑定
 * （RagApplication 仅 @EnableConfigurationProperties(RagProperties.class)，由 Task 1 独占，
 * 不能改动，故不经 @EnableConfigurationProperties 注册）。</p>
 */
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    private String endpoint = "http://localhost:9000";

    private String accessKey = "minioadmin";

    private String secretKey = "minioadmin";

    /** 对象存储桶（路线 §4.3 示例 rag-data，application.yaml 默认 rag）。 */
    private String bucket = "rag";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
