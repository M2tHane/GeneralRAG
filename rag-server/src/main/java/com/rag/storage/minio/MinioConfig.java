package com.rag.storage.minio;

import io.minio.MinioClient;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 装配：MinioClient + ObjectStore（含启动时确保桶存在）。
 * 配置键位为 Task 1 已有 {@code minio.*}（endpoint/access-key/secret-key/bucket）。
 */
@Configuration
public class MinioConfig {

    @Bean
    @ConfigurationProperties(prefix = "minio")
    public MinioProperties minioProperties() {
        return new MinioProperties();
    }

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    public ObjectStore objectStore(MinioClient minioClient, MinioProperties properties) {
        return new ObjectStore(minioClient, properties);
    }
}
