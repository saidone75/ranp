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
    protected NodesApi nodesApi;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Value("${application.consumer-threads}")
    private int consumerThreads;

    @Value("${application.rate-limit-ms:0}")
    private long rateLimitMs;

    @Value("${application.consumer-timeout:5000}")
    private long consumerTimeout;

    @Value("${application.processor-retry-delay-seconds:0}")
    private long processorRetryDelaySeconds;

    @Value("${application.processor-processing-timeout-seconds:3600}")
    private long processorProcessingTimeoutSeconds;

    @Value("${application.processor-max-retry-count:5}")
    private int processorMaxRetryCount;

    @Value("${application.processor-batch-size:10}")
    private int processorBatchSize;

    @Value("${application.read-only:true}")
    protected boolean readOnly;

    /**
     * Starts asynchronous consumption of processable node identifiers from the repository.
     * <p>
     * Processing claims pending or retryable failed node ids until no work is
     * available for the configured consumer timeout. When the timeout is set
     * to {@code 0}, the consumer keeps polling forever and stops only when the
     * worker is interrupted, for example during application shutdown after
     * Ctrl-C. Each claimed document is marked completed or failed after
     * processing, then the worker optionally waits according to the configured
     * rate limit.
     *
     * @param config processor-specific configuration used by
     *               {@link #processNode(String, ProcessorConfig)}
     * @return future representing the asynchronous processing task
     */
    @SneakyThrows
    public CompletableFuture<Void> process(ProcessorConfig config) {
        return CompletableFuture.runAsync(() -> {
            var idleSince = 0L;
            while (!Thread.currentThread().isInterrupted()) {
                val documents = documentProcessingService.claimNextBatch(
                        processorMaxRetryCount,
                        processorRetryDelaySeconds,
                        processorProcessingTimeoutSeconds,
                        processorBatchSize);
                if (documents.isEmpty()) {
                    idleSince = waitForMoreWorkOrTimeout(idleSince);
                    if (idleSince < 0) {
                        break;
                    }
                    continue;
                }
                idleSince = 0L;
                documents.forEach(document -> {
                    var nodeId = document.getNodeId();
                    try {
                        processNode(nodeId, config);
                        documentProcessingService.markCompleted(nodeId);
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
        interruptibleSleep(Math.max(1, consumerThreads) * rateLimitMs);
    }

    /**
     * Waits before checking the repository again after an idle claim attempt,
     * or signals that the configured idle timeout has elapsed.
     *
     * @param idleSince first timestamp, in milliseconds, when the worker became idle
     * @return updated idle timestamp, or {@code -1} when the worker should stop
     */
    private long waitForMoreWorkOrTimeout(long idleSince) {
        if (consumerTimeout <= 0) {
            interruptibleSleep(1000);
            return 0L;
        }

        val now = System.currentTimeMillis();
        val startedAt = idleSince == 0L ? now : idleSince;
        val elapsed = now - startedAt;
        if (elapsed >= consumerTimeout) {
            return -1L;
        }

        interruptibleSleep(Math.min(1000, consumerTimeout - elapsed));
        return startedAt;
    }

    /**
     * Sleeps for the requested delay while preserving interruption requests so
     * application shutdown can stop consumers cleanly.
     *
     * @param delayMillis delay in milliseconds
     */
    private void interruptibleSleep(long delayMillis) {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
