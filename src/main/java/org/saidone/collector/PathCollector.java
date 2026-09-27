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

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import org.alfresco.search.handler.SearchApi;
import org.alfresco.search.model.*;
import org.saidone.entity.CollectorJob;
import org.saidone.model.config.CollectorConfig;
import org.saidone.repository.CollectorJobRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Plans all jobs for an FTS path using disjoint creation-date ranges, then
 * executes them and stores node identifiers associated with each job.
 */
@Component
@RequiredArgsConstructor
public class PathCollector extends AbstractNodeCollector {

    private final SearchApi searchApi;
    private final CollectorJobRepository collectorJobRepository;

    @Override
    public CompletableFuture<Void> collect(CollectorConfig config) {
        // Planning can take a long time without emitting IDs; do not restart and duplicate jobs.
        return CompletableFuture.runAsync(() -> collectNodes(config));
    }

    @Override
    public void collectNodes(CollectorConfig config) {
        if (!(config.getArg("path") instanceof String path) || path.isBlank()) {
            throw new IllegalArgumentException("PathCollector requires a non-blank path");
        }
        int batchSize = 100;
        if (config.getArg("batch-size") != null) {
            try {
                batchSize = Integer.parseInt(config.getArg("batch-size").toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("batch-size must be a positive integer", e);
            }
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch-size must be a positive integer");
        }
        long limit = 1000;
        if (config.getArg("max-nodes-per-job") != null) {
            try {
                limit = Long.parseLong(config.getArg("max-nodes-per-job").toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("max-nodes-per-job must be a positive integer", e);
            }
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("max-nodes-per-job must be a positive integer");
        }
        String query = "PATH:'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'";
        val oldest = search(query, true);
        if (count(oldest) == 0) {
            return;
        }
        long begin = createdAt(oldest);
        long end = createdAt(search(query, false));
        if (begin > end) {
            throw new IllegalStateException("Creation-date bounds changed during path discovery");
        }
        val jobs = new ArrayList<CollectorJob>();
        val ranges = new ArrayDeque<CreatedRange>();
        ranges.push(new CreatedRange(begin, end));
        while (!ranges.isEmpty()) {
            checkInterrupted();
            val range = ranges.pop();
            String rangeQuery = query + " AND cm:created:[" + Instant.ofEpochMilli(range.begin())
                    + " TO " + Instant.ofEpochMilli(range.end()) + "]";
            long nodeCount = count(search(rangeQuery, null));
            if (nodeCount == 0) {
                continue;
            }
            if (nodeCount <= limit) {
                jobs.add(collectorJobRepository.save(new CollectorJob(rangeQuery, nodeCount)));
            } else {
                if (range.begin() == range.end()) {
                    throw new IllegalStateException("Cannot split " + nodeCount + " nodes sharing cm:created="
                            + Instant.ofEpochMilli(range.begin()) + " with max-nodes-per-job=" + limit);
                }
                long middle = range.begin() + (range.end() - range.begin()) / 2;
                ranges.push(new CreatedRange(middle + 1, range.end()));
                ranges.push(new CreatedRange(range.begin(), middle));
            }
        }
        for (val job : jobs) {
            executeJob(job, batchSize);
        }
    }

    private void executeJob(CollectorJob job, int batchSize) {
        checkInterrupted();
        job.setStatus(CollectorJob.STATUS_RUNNING);
        collectorJobRepository.save(job);
        int skipCount = 0;
        boolean hasMore;
        do {
            val result = search(job.getQuery(), null, skipCount, batchSize);
            val entries = result.getList().getEntries();
            val pagination = result.getList().getPagination();
            if (entries == null || pagination == null || pagination.isHasMoreItems() == null) {
                throw new IllegalStateException("Alfresco search response is missing entries or pagination");
            }
            hasMore = pagination.isHasMoreItems();
            if (hasMore && entries.isEmpty()) {
                throw new IllegalStateException("Alfresco returned an empty page with more items available");
            }
            for (val entry : entries) {
                checkInterrupted();
                if (entry == null || entry.getEntry() == null || entry.getEntry().getId() == null) {
                    throw new IllegalStateException("Alfresco search response is missing a node ID");
                }
                collectNode(entry.getEntry().getId(), job.getId());
            }
            skipCount += entries.size();
        } while (hasMore);
        checkInterrupted();
        job.setStatus(CollectorJob.STATUS_COMPLETED);
        collectorJobRepository.save(job);
    }

    private ResultSetPaging search(String query, Boolean ascending) {
        return search(query, ascending, 0, 1);
    }

    @SneakyThrows
    private ResultSetPaging search(String query, Boolean ascending, int skipCount, int maxItems) {
        checkInterrupted();
        val fields = new RequestFields();
        fields.addAll(List.of("id", "createdAt"));
        val request = new SearchRequest()
                .query(new RequestQuery().language(RequestQuery.LanguageEnum.AFTS).query(query))
                .paging(new RequestPagination().skipCount(skipCount).maxItems(maxItems))
                .fields(fields);
        if (ascending != null) {
            val sort = new RequestSortDefinition();
            sort.add(new RequestSortDefinitionInner().type(RequestSortDefinitionInner.TypeEnum.FIELD)
                    .field("cm:created").ascending(ascending));
            request.setSort(sort);
        }
        val result = searchApi.search(request).getBody();
        if (result == null || result.getList() == null) {
            throw new IllegalStateException("Alfresco returned an empty search response for " + query);
        }
        return result;
    }

    private long count(ResultSetPaging result) {
        val pagination = result.getList().getPagination();
        if (pagination == null || pagination.getTotalItems() == null || pagination.getTotalItems() < 0) {
            throw new IllegalStateException("Alfresco search response is missing a valid totalItems count");
        }
        return pagination.getTotalItems();
    }

    private long createdAt(ResultSetPaging result) {
        val entries = result.getList().getEntries();
        if (entries == null || entries.isEmpty() || entries.get(0).getEntry() == null
                || entries.get(0).getEntry().getCreatedAt() == null) {
            throw new IllegalStateException("Alfresco search response is missing the creation-date bound");
        }
        return entries.get(0).getEntry().getCreatedAt().toInstant().toEpochMilli();
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("PathCollector interrupted");
        }
    }

    private record CreatedRange(long begin, long end) { }
}
