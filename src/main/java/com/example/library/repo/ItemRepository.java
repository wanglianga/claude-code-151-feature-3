package com.example.library.repo;

import com.example.library.domain.Item;
import com.example.library.domain.ItemStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {
    Optional<Item> findByBarcode(String barcode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id")
    Optional<Item> lockById(Long id);

    /** 某馆某书目可借（或在馆可被预约调拨）的副本 */
    @Query("select i from Item i where i.bib.id = :bibId and i.currentLibrary.id = :libraryId " +
            "and i.status = :status order by i.barcode")
    List<Item> findByBibAndLibraryAndStatus(Long bibId, Long libraryId, ItemStatus status);

    @Query("select i from Item i where i.bib.id = :bibId and i.status = :status order by i.barcode")
    List<Item> findByBibAndStatus(Long bibId, ItemStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.bib.id = :bibId and i.status = :status order by i.barcode")
    List<Item> lockByBibAndStatus(Long bibId, ItemStatus status);

    long countByBibIdAndCurrentLibraryIdAndStatus(Long bibId, Long libraryId, ItemStatus status);

    long countByBibIdAndCurrentLibraryId(Long bibId, Long libraryId);

    long countByBibIdAndStatus(Long bibId, ItemStatus status);

    long countByCurrentLibraryIdAndStatus(Long libraryId, ItemStatus status);

    /** 仍参与流转的馆藏数（排除丢失/注销），用于外馆保护比例计算 */
    @Query("select count(i) from Item i where i.bib.id = :bibId and i.currentLibrary.id = :libraryId " +
            "and i.status not in :excluded")
    long countCirculatingByBibAndLibrary(@Param("bibId") Long bibId,
                                         @Param("libraryId") Long libraryId,
                                         @Param("excluded") List<ItemStatus> excluded);

    /** 某书目仍参与流转的全部馆藏数（排除丢失/注销），用于热门书需求建议 */
    @Query("select count(i) from Item i where i.bib.id = :bibId and i.status not in :excluded")
    long countCirculatingByBib(@Param("bibId") Long bibId,
                               @Param("excluded") List<ItemStatus> excluded);
}
