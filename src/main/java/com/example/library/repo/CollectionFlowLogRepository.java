package com.example.library.repo;

import com.example.library.domain.CollectionFlowLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionFlowLogRepository extends JpaRepository<CollectionFlowLog, Long> {

    List<CollectionFlowLog> findByBibIdOrderByCreatedAtDesc(Long bibId);

    List<CollectionFlowLog> findByItemIdOrderByCreatedAtDesc(Long itemId);

    List<CollectionFlowLog> findAllByOrderByCreatedAtDesc();
}
