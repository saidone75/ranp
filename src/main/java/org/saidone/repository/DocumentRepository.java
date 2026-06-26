package org.saidone.repository;

import org.saidone.entity.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    boolean existsByStatus(String status);

    long countByStatusIn(Collection<String> statuses);

    long countByStatus(String status);

    Optional<Document> findFirstByStatusOrderByUpdatedAtAsc(String status);

    List<Document> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    Optional<Document> findFirstByStatusAndRetryCountLessThanAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            String status, Integer retryCount, Instant updatedAt);

    List<Document> findByStatusAndRetryCountLessThanAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            String status, Integer retryCount, Instant updatedAt, Pageable pageable);

    @Modifying
    @Query("update Document d set d.status = :status, d.lastError = :lastError where d.nodeId = :nodeId")
    void updateStatus(@Param("nodeId") String nodeId, @Param("status") String status, @Param("lastError") String lastError);

    @Modifying
    @Query("update Document d set d.status = :status, d.lastError = :lastError, d.retryCount = d.retryCount + 1 where d.nodeId = :nodeId")
    void updateFailedStatus(@Param("nodeId") String nodeId, @Param("status") String status, @Param("lastError") String lastError);

}
