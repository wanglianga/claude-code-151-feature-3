package com.example.library.repo;

import com.example.library.domain.ItemMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ItemMovementRepository extends JpaRepository<ItemMovement, Long> {

    @Query("select m from ItemMovement m where m.item.id = :itemId order by m.createdAt, m.id")
    List<ItemMovement> findByItemId(Long itemId);

    @Query("select m from ItemMovement m where m.item.bib.id = :bibId order by m.createdAt, m.id")
    List<ItemMovement> findByBibId(Long bibId);
}
