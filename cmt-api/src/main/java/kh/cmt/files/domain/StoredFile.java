package kh.cmt.files.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.shared.domain.BaseEntity;

/**
 * An uploaded file: metadata only, the bytes are in object storage under {@code key} (and the thumbnail under
 * {@code thumbnailKey}). PENDING until the upload is confirmed and checked, then READY.
 */
@Entity
@Table(name = "file")
public class StoredFile extends BaseEntity {

    public enum Status { PENDING, READY }

    private String bucket;

    @Column(name = "key")
    private String objectKey;

    private String mime;

    private long size;

    private String sha256;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Enumerated(EnumType.STRING)
    private FilePurpose purpose;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "thumbnail_key")
    private String thumbnailKey;

    private Integer width;

    private Integer height;

    private boolean attached;

    /** Checked and stored: final size, fingerprint and (images) dimensions. */
    public void ready(long size, String sha256, Integer width, Integer height, String thumbnailKey) {
        this.size = size;
        this.sha256 = sha256;
        this.width = width;
        this.height = height;
        this.thumbnailKey = thumbnailKey;
        this.status = Status.READY;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getSha256() {
        return sha256;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(UUID uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public FilePurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(FilePurpose purpose) {
        this.purpose = purpose;
    }

    public Status getStatus() {
        return status;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public boolean isAttached() {
        return attached;
    }

    public void setAttached(boolean attached) {
        this.attached = attached;
    }
}
