package kh.cmt.files.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The object storage (S3 API). Two clients: one for the API's own calls, one that only signs links with the host
 * the apps can reach ({@code publicEndpoint}); signing needs no network call because the region is fixed. The bucket is
 * created on first use, so the API starts even when the store is not up yet.
 */
@Component
@EnableConfigurationProperties(FilesProperties.class)
public class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private final FilesProperties props;
    private final MinioClient client;
    private final MinioClient signer;
    private volatile boolean bucketReady;

    ObjectStore(FilesProperties props) {
        this.props = props;
        this.client = MinioClient.builder().endpoint(props.endpoint()).credentials(props.accessKey(), props.secretKey())
                .region(props.region()).build();
        this.signer = MinioClient.builder().endpoint(props.publicOrInternalEndpoint())
                .credentials(props.accessKey(), props.secretKey()).region(props.region()).build();
    }

    public String bucket() {
        return props.bucket();
    }

    /** A link to upload the object with HTTP PUT, valid for the upload-link lifetime. */
    public String uploadLink(String key) {
        ensureBucket();
        return call(() -> signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
                .bucket(props.bucket()).object(key).expiry((int) props.uploadLinkTtl().toSeconds(), TimeUnit.SECONDS).build()));
    }

    /** A link to read the object, shown in the browser ({@code inline}) under {@code fileName}. */
    public String downloadLink(String key, String mime, String fileName) {
        String safeName = fileName == null ? "file" : fileName.replaceAll("[\"\\\\\\r\\n]", "_");
        return call(() -> signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                .bucket(props.bucket()).object(key).expiry((int) props.downloadLinkTtl().toSeconds(), TimeUnit.SECONDS)
                .extraQueryParams(Map.of("response-content-type", mime,
                        "response-content-disposition", "inline; filename=\"" + safeName + "\""))
                .build()));
    }

    public Duration downloadLinkTtl() {
        return props.downloadLinkTtl();
    }

    public Duration uploadLinkTtl() {
        return props.uploadLinkTtl();
    }

    /** The size of the stored object; empty if nothing was uploaded under that key. */
    public OptionalLong size(String key) {
        try {
            return OptionalLong.of(client.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(key).build()).size());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return OptionalLong.empty();
            }
            throw new IllegalStateException("Object storage: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Object storage: " + e.getMessage(), e);
        }
    }

    public byte[] read(String key) {
        return call(() -> {
            try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(props.bucket()).object(key).build())) {
                return in.readAllBytes();
            }
        });
    }

    public void write(String key, byte[] bytes, String mime) {
        ensureBucket();
        call(() -> client.putObject(PutObjectArgs.builder().bucket(props.bucket()).object(key)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType(mime).build()));
    }

    /** Removes the object; a missing one is fine. Failures are logged, not thrown (the record is already gone). */
    public void remove(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(props.bucket()).object(key).build());
        } catch (Exception e) {
            log.warn("Could not remove object {} from storage: {}", key, e.toString());
        }
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        call(() -> {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
            }
            return null;
        });
        bucketReady = true;
    }

    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T call(StoreCall<T> work) {
        try {
            return work.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Object storage: " + e.getMessage(), e);
        }
    }
}
