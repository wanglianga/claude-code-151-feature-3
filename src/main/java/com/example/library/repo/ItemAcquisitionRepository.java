package com.example.library.repo;

import com.example.library.domain.ItemAcquisition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemAcquisitionRepository extends JpaRepository<ItemAcquisition, Long> {

    List<ItemAcquisition> findByBibIdOrderByCreatedAtDesc(Long bibId);

    List<ItemAcquisition> findAllByOrderByCreatedAtDesc();
}
