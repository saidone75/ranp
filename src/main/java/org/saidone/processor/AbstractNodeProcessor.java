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

package org.saidone.processor;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.core.handler.NodesApi;
import org.alfresco.core.model.Node;
import org.saidone.component.BaseComponent;
import org.saidone.model.config.ProcessorConfig;
import org.saidone.service.DocumentProcessingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base implementation of {@link NodeProcessor} that consumes node identifiers
 * from the persistent repository and delegates the concrete operation to
 * {@link #processNode(String, ProcessorConfig)}.
 * <p>
 * Subclasses typically use the {@link #getNode(String)} helper methods to
 * retrieve node metadata and should honor the {@link #readOnly} flag to avoid
 * write operations when running in dry-run mode.
 */
@Slf4j
public abstract class AbstractNodeProcessor extends BaseComponent implements NodeProcessor {

    @Autowired
    private AtomicInteger processedNodesCounter;

    @Autowired
    protected NodesApi nodesApi;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Value("${application.consumer-threads}")
    private int consumerThreads;

    @Value("${application.rate-limit-ms:0}")
    private long rateLimitMs;

    @Value("${application.processor-retry-delay-seconds:0}")
    private long processorRetryDelaySeconds;

    @Value("${application.processor-max-retry-count:5}")
    private int processorMaxRetryCount;

    @Value("${application.processor-batch-size:10}")
    private int processorBatchSize;

    @Value("${application.read-only:true}")
    protected boolean readOnly;

    /**
     * Starts asynchronous consumption of processable node identifiers from the repository.
     * <p>
     * Processing stops when no pending or retryable failed node id is
     * available. Each successfully processed node increments the shared
     * counter and optionally waits according to the configured rate limit.
     *
     * @param config processor-specific configuration used by
     *               {@link #processNode(String, ProcessorConfig)}
     * @return future representing the asynchronous processing task
     */
    @SneakyThrows
    public CompletableFuture<Void> process(ProcessorConfig config) {
        return CompletableFuture.runAsync(() -> {
            while (true) {
                val documents = documentProcessingService.claimNextBatch(
                        processorMaxRetryCount,
                        processorRetryDelaySeconds,
                        processorBatchSize);
                if (documents.isEmpty()) {
                    break;
                }
                documents.forEach(document -> {
                    var nodeId = document.getNodeId();
                    try {
                        processNode(nodeId, config);
                        documentProcessingService.markCompleted(nodeId);
                        processedNodesCounter.incrementAndGet();
                    } catch (Exception e) {
                        log.trace(e.getMessage(), e);
                        log.error(e.getMessage());
                        documentProcessingService.markFailed(nodeId, e);
                    } finally {
                        sleep();
                    }
                });
            }
        });
    }

    /**
     * Loads a node by id without requesting additional include parameters.
     *
     * @param nodeId Alfresco node id
     * @return the fetched node entry
     */
    protected Node getNode(String nodeId) {
        return getNode(nodeId, false);
    }

    /**
     * Loads a node by id, optionally requesting its properties.
     *
     * @param nodeId            Alfresco node id
     * @param includeProperties whether to include {@code properties} in the
     *                          API response
     * @return the fetched node entry
     */
    protected Node getNode(String nodeId, boolean includeProperties) {
        return Objects.requireNonNull(nodesApi.getNode(
                nodeId,
                includeProperties ? List.of("properties") : null,
                null,
                null).getBody()).getEntry();
    }

    /**
     * Loads a node by id with the provided include parameters.
     *
     * @param nodeId  Alfresco node id
     * @param include include flags to pass to the API (for example,
     *                {@code properties} or {@code path})
     * @return the fetched node entry
     */
    protected Node getNode(String nodeId, List<String> include) {
        return Objects.requireNonNull(nodesApi.getNode(
                nodeId,
                include,
                null,
                null).getBody()).getEntry();
    }

    /**
     * Applies the configured delay between processed nodes.
     * <p>
     * The delay is skipped when the configured rate limit is not positive.
     */
    @SneakyThrows
    private void sleep() {
        if (rateLimitMs <= 0) {
            return;
        }
        TimeUnit.MILLISECONDS.sleep(Math.max(1, consumerThreads) * rateLimitMs);
    }

}
