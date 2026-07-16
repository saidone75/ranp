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
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.search.handler.SearchApi;
import org.alfresco.search.model.*;
import org.saidone.entity.CollectorJob;
import org.saidone.model.config.CollectorConfig;
import org.saidone.repository.CollectorJobRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Executes an Alfresco FTS query and stores the resulting node identifiers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueryNodeCollector extends AbstractNodeCollector {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final long DEFAULT_MAX_NODES_PER_JOB = 100000L;
    private static final String QUERY_ARG = "query";
    private static final String MIN_CREATED_ARG = "min-created";
    private static final String MAX_CREATED_ARG = "max-created";
    private static final String MAX_NODES_PER_JOB_ARG = "max-nodes-per-job";
    private static final String BATCH_SIZE_ARG = "batch-size";
    private static final Pattern CREATED_RANGE_PATTERN = Pattern.compile("cm:created:\\s*\\[[^]]*]", Pattern.CASE_INSENSITIVE);

    private int batchSize = DEFAULT_BATCH_SIZE;
    private static final RequestFields REQUEST_FIELDS;
    static {
        REQUEST_FIELDS = new RequestFields();
        REQUEST_FIELDS.addAll(List.of("isLink", "parentId", "isFile", "versionComment", "search", "createdByUser", "name", "allowableOperations", "aspectNames", "properties", "isLocked", "archivedAt", "isFolder", "content", "id", "nodeType", "path", "isFavorite", "modifiedByUser", "createdAt", "modifiedAt", "archivedByUser", "versionLabel"));
    }

    private final SearchApi searchApi;
    private final CollectorJobRepository collectorJobRepository;

    @SneakyThrows
    private ResultSetPaging search(String query, int skipCount, int maxItems) {
        val searchRequest = new SearchRequest();
        val requestQuery = new RequestQuery();
        requestQuery.setLanguage(RequestQuery.LanguageEnum.AFTS);
        requestQuery.setQuery(query);
        val paging = new RequestPagination();
        paging.setMaxItems(maxItems);
        paging.setSkipCount(skipCount);
        searchRequest.setQuery(requestQuery);
        searchRequest.setPaging(paging);
        searchRequest.setFields(REQUEST_FIELDS);
        return searchApi.search(searchRequest).getBody();
    }

    private long countNodes(String query) {
        val resultSetPaging = search(query, 0, 1);
        return resultSetPaging.getList().getPagination().getTotalItems();
    }

    private void doQuery(CollectorJob job) {
        var skipCount = 0;
        ResultSetPaging resultSetPaging;
        job.setStatus(CollectorJob.STATUS_RUNNING);
        collectorJobRepository.save(job);
        do {
            log.debug("job {} skipCount --> {}", job.getId(), skipCount);
            resultSetPaging = search(job.getQuery(), skipCount, batchSize);
            for (val e : resultSetPaging.getList().getEntries()) {
                collectNode(e.getEntry().getId(), job.getId());
            }
            skipCount += batchSize;
        } while (resultSetPaging.getList().getPagination().isHasMoreItems());
        job.setStatus(CollectorJob.STATUS_COMPLETED);
        collectorJobRepository.save(job);
    }

    /**
     * Executes the configured Alfresco FTS query and stores each returned
     * node identifier.
     *
     * @param config collector configuration
     */
    @Override
    public void collectNodes(CollectorConfig config) {
        this.batchSize = getIntArg(config, BATCH_SIZE_ARG, DEFAULT_BATCH_SIZE);
        val maxNodesPerJob = getLongArg(config, MAX_NODES_PER_JOB_ARG, DEFAULT_MAX_NODES_PER_JOB);
        val query = (String) config.getArg(QUERY_ARG);
        if (config.getArg(MIN_CREATED_ARG) == null || config.getArg(MAX_CREATED_ARG) == null) {
            val count = countNodes(query);
            doQuery(collectorJobRepository.save(new CollectorJob(query, count)));
            return;
        }
        val minCreated = Instant.parse((String) config.getArg(MIN_CREATED_ARG));
        val maxCreated = Instant.parse((String) config.getArg(MAX_CREATED_ARG));
        for (val job : createJobs(query, minCreated, maxCreated, maxNodesPerJob)) {
            doQuery(job);
        }
    }

    private List<CollectorJob> createJobs(String query, Instant minCreated, Instant maxCreated, long maxNodesPerJob) {
        val ranges = new ArrayDeque<CreatedRange>();
        val jobs = new java.util.ArrayList<CollectorJob>();
        ranges.add(new CreatedRange(minCreated, maxCreated));
        while (!ranges.isEmpty()) {
            val range = ranges.remove();
            val rangeQuery = withCreatedRange(query, range);
            val count = countNodes(rangeQuery);
            if (count > maxNodesPerJob && range.canSplit()) {
                val middle = range.middle();
                ranges.add(new CreatedRange(range.min(), middle));
                ranges.add(new CreatedRange(middle.plusMillis(1), range.max()));
            } else {
                jobs.add(collectorJobRepository.save(new CollectorJob(rangeQuery, count)));
            }
        }
        return jobs;
    }

    private String withCreatedRange(String query, CreatedRange range) {
        val rangeQuery = "cm:created:[" + range.min() + " TO " + range.max() + "]";
        if (CREATED_RANGE_PATTERN.matcher(query).find()) {
            return CREATED_RANGE_PATTERN.matcher(query).replaceFirst(rangeQuery);
        }
        return "(" + query + ") AND " + rangeQuery;
    }

    private int getIntArg(CollectorConfig config, String name, int defaultValue) {
        return config.getArg(name) == null ? defaultValue : ((Number) config.getArg(name)).intValue();
    }

    private long getLongArg(CollectorConfig config, String name, long defaultValue) {
        return config.getArg(name) == null ? defaultValue : ((Number) config.getArg(name)).longValue();
    }

    private record CreatedRange(Instant min, Instant max) {
        boolean canSplit() {
            return min.toEpochMilli() < max.toEpochMilli();
        }

        Instant middle() {
            return Instant.ofEpochMilli(min.toEpochMilli() + ((max.toEpochMilli() - min.toEpochMilli()) / 2));
        }
    }

}
