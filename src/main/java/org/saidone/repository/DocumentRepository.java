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

package org.saidone.repository;

import org.saidone.entity.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    
    long countByStatus(String status);
    
    List<Document> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    List<Document> findByStatusAndRetryCountLessThanAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            String status, Integer retryCount, String updatedAt, Pageable pageable);

    @Modifying
    @Query("update Document d set d.status = :status, d.lastError = :lastError, d.updatedAt = :updatedAt where d.nodeId = :nodeId")
    void updateStatus(@Param("nodeId") String nodeId, @Param("status") String status, @Param("lastError") String lastError,
                      @Param("updatedAt") String updatedAt);

    @Modifying
    @Query("update Document d set d.status = :status, d.lastError = :lastError, d.retryCount = d.retryCount + 1, d.updatedAt = :updatedAt where d.nodeId = :nodeId")
    void updateFailedStatus(@Param("nodeId") String nodeId, @Param("status") String status, @Param("lastError") String lastError,
                            @Param("updatedAt") String updatedAt);

}
