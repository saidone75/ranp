/*
 *  Alfresco Resilient Node Processor - Do things with nodes
 *  Copyright (C) 2023-2026 Saidone
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.saidone.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.val;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "node_ids")
@NoArgsConstructor
@Data
public class Document {

    private static final DateTimeFormatter READABLE_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

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
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    public Document(String nodeId) {
        this.nodeId = nodeId;
    }

    @PrePersist
    protected void onCreate() {
        val now = nowAsReadableTimestamp();
        setCreatedAt(now);
        setUpdatedAt(now);
    }

    @PreUpdate
    protected void onUpdate() {
        setUpdatedAt(nowAsReadableTimestamp());
    }

    public static String nowAsReadableTimestamp() {
        return readableTimestampMinusSeconds(0);
    }

    public static String readableTimestampMinusSeconds(long seconds) {
        return LocalDateTime.now(ZoneOffset.UTC)
                .minusSeconds(seconds)
                .format(READABLE_TIMESTAMP_FORMATTER);
    }

    public void increaseRetryCount() {
        this.retryCount += 1;
    }

}
