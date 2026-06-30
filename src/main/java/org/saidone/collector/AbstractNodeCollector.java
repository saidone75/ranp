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

package org.saidone.collector;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.saidone.component.BaseComponent;
import org.saidone.entity.Document;
import org.saidone.model.config.CollectorConfig;
import org.saidone.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base implementation of {@link NodeCollector} providing repository storage and
 * a default asynchronous execution of {@link #collectNodes(CollectorConfig)}.
 */
@Slf4j
public abstract class AbstractNodeCollector extends BaseComponent implements NodeCollector {

    @Autowired
    private DocumentRepository documentRepository;

    @Value("${application.collector-restart-timeout:0}")
    private long collectorRestartTimeout;

    private final AtomicLong lastCollectedAt = new AtomicLong();

    /**
     * Collects nodes asynchronously by delegating to
     * {@link #collectNodes(CollectorConfig)}.
     *
     * @param config collector configuration
     * @return future representing the asynchronous task
     */
    @SneakyThrows
    @Override
    public CompletableFuture<Void> collect(CollectorConfig config) {
        return CompletableFuture.runAsync(() -> {
            if (collectorRestartTimeout <= 0) {
                collectNodes(config);
                return;
            }

            while (!Thread.currentThread().isInterrupted()) {
                val executor = Executors.newSingleThreadExecutor();
                try {
                    lastCollectedAt.set(System.currentTimeMillis());
                    val collectorTask = executor.submit(() -> collectNodes(config));
                    if (waitForCollector(collectorTask)) {
                        return;
                    }
                    log.warn("Collector {} did not collect nodes for {} s; restarting",
                            config.getName(), collectorRestartTimeout);
                } finally {
                    executor.shutdownNow();
                }
            }
        });
    }

    /**
     * Stores a collected node identifier in the persistent repository.
     * <p>
     * Existing identifiers are left untouched so collectors do not reset a
     * processor-managed status when the same node is discovered more than once.
     *
     * @param nodeId Alfresco node id to persist
     */
    protected void collectNode(String nodeId) {
        if (nodeId == null) {
            return;
        }
        lastCollectedAt.set(System.currentTimeMillis());
        if (documentRepository.existsById(nodeId)) {
            return;
        }
        try {
            documentRepository.save(new Document(nodeId));
        } catch (DataIntegrityViolationException e) {
            log.debug("Node {} was already stored by another collector", nodeId);
        }
    }

    /**
     * Waits for a collector task to finish or become idle long enough to be restarted.
     *
     * @param collectorTask running collector task
     * @return {@code true} when the collector completed, {@code false} when it was cancelled for restart
     */
    private boolean waitForCollector(Future<?> collectorTask) {
        while (!Thread.currentThread().isInterrupted()) {
            if (collectorTask.isDone()) {
                propagateCollectorFailure(collectorTask);
                return true;
            }
            if (System.currentTimeMillis() - lastCollectedAt.get() >= 1000L * collectorRestartTimeout) {
                collectorTask.cancel(true);
                return false;
            }
            interruptibleSleep(Math.min(1000, 1000L * collectorRestartTimeout));
        }
        collectorTask.cancel(true);
        return true;
    }

    /**
     * Re-throws collector failures so the main application can fail fast.
     *
     * @param collectorTask completed collector task
     */
    private void propagateCollectorFailure(Future<?> collectorTask) {
        try {
            collectorTask.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * Sleeps for the requested delay while preserving interruption requests.
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
