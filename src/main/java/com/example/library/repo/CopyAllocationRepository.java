package com.example.library.repo;

import com.example.library.domain.CopyAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CopyAllocationRepository extends JpaRepository<CopyAllocation, Long> {

    List<CopyAllocation> findByAcquisitionIdOrderById(Long acquisitionId);
}
