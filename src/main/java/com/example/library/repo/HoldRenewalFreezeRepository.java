package com.example.library.repo;

import com.example.library.domain.HoldRenewalFreeze;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface HoldRenewalFreezeRepository extends JpaRepository<HoldRenewalFreeze, Long> {

    @Query("select f from HoldRenewalFreeze f where f.bib.id = :bibId and f.active = true order by f.createdAt desc")
    List<HoldRenewalFreeze> findActiveByBib(Long bibId);

    @Query("select f from HoldRenewalFreeze f where f.active = true order by f.createdAt desc")
    List<HoldRenewalFreeze> findAllActive();
}
