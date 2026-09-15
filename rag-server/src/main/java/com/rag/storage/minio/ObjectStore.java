package com.rag.storage.minio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.rag.domain.exception.DomainException;
import com.rag.domain.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对象存储门面（路线 §4.3）：桶内对象键严格为
 *
 * <pre>
 * ragsource/{kbId}/{docId}/source.{pdf|md|txt}   原始上传文件（原样字节）
 * ragsource/{kbId}/{docId}/parsed.txt            解析+清洗产物（PARSING 完成标志）
 * </pre>
 *
 * <p>键只由 UUID 组成，<b>用户文件名永不进入对象键</b>（规避路径注入/非法字符，
 * 路线 §7）；文件名仅用于提取扩展名且必须命中白名单 pdf/md/txt。
 * 删除按前缀列举 + 批量删（文档删 {@code ragsource/{kbId}/{docId}/}，
 * KB 删 {@code ragsource/{kbId}/}），供 Task 3 CleanupService 补偿调用。</p>
 */
@Component
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    /** 扩展名白名单（契约与路线 §7；R3-P1 扩展 docx/xlsx/csv）。 */
    public static final Set<String> SOURCE_EXTENSIONS = Set.of("pdf", "md", "txt", "docx", "xlsx", "csv");

    private static final String PARSED_OBJECT_NAME = "parsed.txt";

    private final MinioClient client;
    private final String bucket;

    public ObjectStore(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.bucket = properties.getBucket();
    }

    /** 桶不存在则自动创建（应用启动即校验存储可用性）。 */
    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO 桶 {}", bucket);
            }
        } catch (Exception e) {
            throw wrap("确保桶存在失败（bucket=" + bucket + "）", e);
        }
    }

    /** 源对象键（公开给 CleanupService/service 层拼 payload 用）。 */
    public static String sourceKey(String kbId, String docId, String extension) {
        return "ragsource/" + kbId + "/" + docId + "/source." + extension;
    }

    /** 解析产物对象键。 */
    public static String parsedKey(String kbId, String docId) {
        return "ragsource/" + kbId + "/" + docId + "/" + PARSED_OBJECT_NAME;
    }

    /**
     * 上传原始文件。扩展名从 fileName 提取（小写），不在白名单 →
     * {@link ErrorCode#UNSUPPORTED_FILE_TYPE}（415）；文件名本身不进入对象键。
     *
     * @return 实际写入的对象键
     */
    public String putSource(String kbId, String docId, String fileName, InputStream stream, long size) {
        String ext = extractExtension(fileName);
        String key = sourceKey(kbId, docId, ext);
        put(key, stream, size, contentTypeOf(ext));
        return key;
    }

    /** 上传解析+清洗产物文本（UTF-8）。存在即 PARSING 阶段完成标志。 */
    public void putParsed(String kbId, String docId, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        put(parsedKey(kbId, docId), new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=UTF-8");
    }

    /** 读原始文件字节流（调用方负责关闭）。扩展名来自 document.sourceObjectKey 记录值。 */
    public InputStream getSource(String kbId, String docId, String extension) {
        return get(sourceKey(kbId, docId, extension));
    }

    /** 读解析+清洗产物文本（UTF-8）。 */
    public String getParsed(String kbId, String docId) {
        try (InputStream in = get(parsedKey(kbId, docId))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw wrap("读取解析产物失败：" + parsedKey(kbId, docId), e);
        }
    }

    /** 解析产物是否存在（重试续跑的 PARSING 完成证据）。 */
    public boolean existsParsed(String kbId, String docId) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(parsedKey(kbId, docId)).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw wrap("探测解析产物失败：" + parsedKey(kbId, docId), e);
        } catch (Exception e) {
            throw wrap("探测解析产物失败：" + parsedKey(kbId, docId), e);
        }
    }

    /** 删除某文档全部对象（source.* + parsed.txt）。 */
    public void removeDoc(String kbId, String docId) {
        removeByPrefix("ragsource/" + kbId + "/" + docId + "/");
    }

    /** 删除某知识库全部对象（KB 删除补偿）。 */
    public void removeKb(String kbId) {
        removeByPrefix("ragsource/" + kbId + "/");
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void put(String key, InputStream stream, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw wrap("写入对象失败：" + key, e);
        }
    }

    private InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw wrap("读取对象失败：" + key, e);
        }
    }

    private void removeByPrefix(String prefix) {
        try {
            List<DeleteObject> objects = new ArrayList<>();
            // recursive(true)：按目录层级的前缀（如 ragsource/{kb}/）在 delimiter 模式下只返回
            // CommonPrefix 而非对象，会导致 KB 级清理删不到任何对象
            for (io.minio.Result<io.minio.messages.Item> result : client.listObjects(
                    io.minio.ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())) {
                objects.add(new DeleteObject(result.get().objectName()));
            }
            if (objects.isEmpty()) {
                return;
            }
            Iterable<io.minio.Result<DeleteError>> results = client.removeObjects(
                    RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build());
            List<String> failures = new ArrayList<>();
            for (io.minio.Result<DeleteError> result : results) {
                DeleteError error = result.get();
                if (error != null) {
                    failures.add(error.objectName() + ": " + error.message());
                }
            }
            if (!failures.isEmpty()) {
                throw wrap("按前缀删除存在失败项（prefix=" + prefix + "）："
                        + String.join("; ", failures), null);
            }
            log.debug("MinIO 前缀删除完成：prefix={}, objects={}", prefix, objects.size());
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw wrap("按前缀删除失败：" + prefix, e);
        }
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SOURCE_EXTENSIONS.contains(ext)) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        return ext;
    }

    private static String contentTypeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "md" -> "text/markdown; charset=UTF-8";
            case "csv" -> "text/csv; charset=UTF-8";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "text/plain; charset=UTF-8";
        };
    }

    private static DomainException wrap(String message, Exception cause) {
        if (cause instanceof DomainException de) {
            return de;
        }
        String detail = cause == null ? "" : "：" + cause.getMessage();
        log.error("MinIO 操作失败：{}", message, cause);
        return new DomainException(ErrorCode.INTERNAL_ERROR, "MinIO 操作失败：" + message + detail);
    }
}
