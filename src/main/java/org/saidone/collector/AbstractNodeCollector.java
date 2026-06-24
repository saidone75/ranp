/*
 *  Alfresco Node Processor - Do things with nodes
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
import org.saidone.component.BaseComponent;
import org.saidone.entity.Document;
import org.saidone.model.config.CollectorConfig;
import org.saidone.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.CompletableFuture;

/**
 * Base implementation of {@link NodeCollector} providing repository storage and
 * a default asynchronous execution of {@link #collectNodes(CollectorConfig)}.
 */
@Slf4j
public abstract class AbstractNodeCollector extends BaseComponent implements NodeCollector {

    @Autowired
    private DocumentRepository documentRepository;

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
        return CompletableFuture.runAsync(() -> collectNodes(config));
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
        if (nodeId == null || documentRepository.existsById(nodeId)) {
            return;
        }
        try {
            documentRepository.save(new Document(nodeId));
        } catch (DataIntegrityViolationException e) {
            log.debug("Node {} was already stored by another collector", nodeId);
        }
    }

}
