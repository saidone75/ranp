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

package org.saidone.service;

import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.entity.Document;
import org.saidone.repository.DocumentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates processor access to persisted node identifiers and status
 * transitions.
 */
@RequiredArgsConstructor
@Service
public class DocumentProcessingService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final DocumentRepository documentRepository;

    /**
     * Claims the next processable documents in a single repository round-trip per
     * status, preferring never-processed nodes and filling the remaining slots with
     * retryable failures whose delay has elapsed.
     */
    @Transactional
    public synchronized List<Document> claimNextBatch(int maxRetryCount, long retryDelaySeconds, int batchSize) {
        val safeBatchSize = Math.max(1, batchSize);
        val documents = new ArrayList<Document>(safeBatchSize);

        documents.addAll(documentRepository.findByStatusOrderByUpdatedAtAsc(
                Document.STATUS_PENDING, PageRequest.of(0, safeBatchSize)));

        val remaining = safeBatchSize - documents.size();
        if (remaining > 0) {
            documents.addAll(documentRepository.findByStatusAndRetryCountLessThanAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                    Document.STATUS_FAILED,
                    maxRetryCount,
                    Instant.now().minusSeconds(retryDelaySeconds).toEpochMilli(),
                    PageRequest.of(0, remaining)));
        }

        documents.forEach(d -> {
            d.setStatus(Document.STATUS_PROCESSING);
            d.setLastError(null);
        });
        return documentRepository.saveAll(documents);
    }

    @Transactional
    public void markCompleted(String nodeId) {
        documentRepository.updateStatus(nodeId, Document.STATUS_COMPLETED, null);
    }

    @Transactional
    public void markFailed(String nodeId, Exception exception) {
        documentRepository.updateFailedStatus(nodeId, Document.STATUS_FAILED, truncateError(exception));
    }

    private String truncateError(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getName();
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

}
