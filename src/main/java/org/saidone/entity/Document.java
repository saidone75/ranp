package org.saidone.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.val;

import java.time.Instant;

@Entity
@Table(name = "node_ids")
@NoArgsConstructor
@Data
public class Document {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "node_id", length = 36)
    private String nodeId;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Document(String nodeId) {
        this.nodeId = nodeId;
    }

    @PrePersist
    protected void onCreate() {
        val instant = Instant.now();
        setCreatedAt(instant);
        setUpdatedAt(instant);
    }

    @PreUpdate
    protected void onUpdate() {
        setUpdatedAt(Instant.now());
    }

    public void increaseRetryCount() {
        this.retryCount += 1;
    }

}
