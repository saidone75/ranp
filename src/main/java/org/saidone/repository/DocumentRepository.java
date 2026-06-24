package org.saidone.repository;

import org.saidone.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    boolean existsByStatus(String status);

    long countByStatusIn(Collection<String> statuses);

    long countByStatus(String status);

}
