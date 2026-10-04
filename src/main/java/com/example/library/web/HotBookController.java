package com.example.library.web;

import com.example.library.service.DemandService;
import com.example.library.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/hot-books")
@Tag(name = "11. 热门书需求调控（管理端）",
        description = "预约人数过多时：按等待人数/复本数量/续借限制/跨馆调拨成本建议采购或暂停续借；"
                + "新增复本到馆后按预约时间与读者所在馆公平分配（避免全部流向总馆）；"
                + "馆员可查看分配建议并人工调整，调整原因进入馆藏流转记录")
public class HotBookController {

    private final DemandService demand;

    public HotBookController(DemandService demand) {
        this.demand = demand;
    }

    @GetMapping("/{bibId}/demand-advice")
    @Operation(summary = "需求调控建议：综合等待人数、复本数量、续借限制与跨馆调拨成本，建议新增采购或暂停续借")
    public DemandAdviceView demandAdvice(@PathVariable Long bibId) {
        return demand.demandAdvice(bibId);
    }

    @PutMapping("/{bibId}/renewal-suspension")
    @Operation(summary = "（馆员）暂停/恢复该书续借；暂停必须填写理由，生效后该书一律不可续借")
    public BibRenewalView renewalSuspension(@PathVariable Long bibId,
                                            @Valid @RequestBody RenewalSuspensionRequest req) {
        return demand.setRenewalSuspension(bibId, req.suspended(), req.staff(), req.reason());
    }

    @GetMapping("/{bibId}/allocation-plan")
    @Operation(summary = "新增复本分配建议预览：按预约时间+读者所在馆+各分馆等待均衡计算每册去向与预计取书窗口")
    public AllocationPlanView allocationPlan(@PathVariable Long bibId,
                                             @RequestParam String arrivalLibraryCode,
                                             @RequestParam(defaultValue = "1") int copies) {
        return demand.allocationPlan(bibId, arrivalLibraryCode, copies);
    }

    @PostMapping("/{bibId}/acquisitions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "（馆员）新增复本采购到馆：按建议自动分配，可用 overrides 人工调整；"
            + "与建议不一致时必须填 adjustReason，调整原因进入馆藏流转记录")
    public AcquisitionResultView acquire(@PathVariable Long bibId,
                                         @Valid @RequestBody AcquisitionRequest req) {
        return demand.acquire(bibId, req);
    }

    @GetMapping("/items/{barcode}/movements")
    @Operation(summary = "单册馆藏流转记录（采购到馆/自动分配/人工调整及原因）")
    public List<MovementView> itemMovements(@PathVariable String barcode) {
        return demand.movementsForItem(barcode);
    }

    @GetMapping("/{bibId}/movements")
    @Operation(summary = "某书目全部复本的馆藏流转记录")
    public List<MovementView> bibMovements(@PathVariable Long bibId) {
        return demand.movementsForBib(bibId);
    }
}
