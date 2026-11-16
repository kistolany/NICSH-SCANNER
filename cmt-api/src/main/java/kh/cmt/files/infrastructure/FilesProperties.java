package kh.cmt.files.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code cmt.files.*}: the object storage (MinIO in development, any S3-compatible store in
 * production) and how long the links live.
 *
 * @param endpoint       how the API reaches the store
 * @param publicEndpoint how the apps reach it (the host in upload / download links); the endpoint when not set
 * @param uploadLinkTtl  lifetime of an upload link
 * @param downloadLinkTtl lifetime of a download link
 */
@ConfigurationProperties("cmt.files")
public record FilesProperties(String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket,
        String region, Duration uploadLinkTtl, Duration downloadLinkTtl) {

    public String publicOrInternalEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    }
}
