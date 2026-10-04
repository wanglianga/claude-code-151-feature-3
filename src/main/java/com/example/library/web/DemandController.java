package com.example.library.web;

import com.example.library.service.CopyAllocationService;
import com.example.library.service.DemandService;
import com.example.library.web.dto.admin.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 热门书预约队列调整（馆员侧）：
 * 需求建议（新增采购/暂停续借）、采购批次到馆与逐册均衡分配建议、
 * 馆员手动改派（原因入馆藏流转记录）、确认执行、暂停/解除续借、流转记录查询。
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "11. 热门书队列调整",
        description = "按等待人数/复本/续借/调拨成本给出采购与暂停续借建议；新复本按预约时间与分馆等待均衡分配，馆员可改派并留痕")
public class DemandController {

    private final DemandService demand;
    private final CopyAllocationService allocations;

    public DemandController(DemandService demand, CopyAllocationService allocations) {
        this.demand = demand;
        this.allocations = allocations;
    }

    // ---------------- 需求建议 ----------------

    @GetMapping("/demand/advice")
    @Operation(summary = "全部热门书需求建议（按等待/复本比降序）：是否建议新增采购及册数、是否建议暂停续借、各馆等待画像")
    public List<DemandAdviceView> advice() {
        return demand.adviceList();
    }

    @GetMapping("/demand/advice/{bibId}")
    @Operation(summary = "单部书的需求建议（等待人数、复本、可续借在借、跨馆调剂成本、规则依据文案）")
    public DemandAdviceView adviceOne(@PathVariable Long bibId) {
        return demand.advice(bibId);
    }

    // ---------------- 采购批次与新复本分配 ----------------

    @PostMapping("/acquisitions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记新采购复本到馆：系统同事务按预约时间+分馆等待时长生成逐册均衡分配建议")
    public AcquisitionView register(@Valid @RequestBody AcquisitionRequest req) {
        return allocations.registerView(req.bibId(), req.copyCount(),
                req.receivingLibraryCode(), req.staff(), req.note());
    }

    @GetMapping("/acquisitions")
    @Operation(summary = "采购批次列表（可按书目过滤）")
    public List<AcquisitionView> acquisitions(@RequestParam(required = false) Long bibId) {
        return allocations.acquisitionViews(bibId);
    }

    @GetMapping("/acquisitions/{id}")
    @Operation(summary = "采购批次详情（含每册分配建议、预计取书窗口、状态）")
    public AcquisitionView acquisition(@PathVariable Long id) {
        return allocations.acquisitionView(id);
    }

    @PostMapping("/allocations/{id}/adjust")
    @Operation(summary = "馆员手动调整某册分配建议（改派预约或跳过上架）；staff 与 reason 必填，原因写入馆藏流转记录")
    public CopyAllocationView adjust(@PathVariable Long id, @Valid @RequestBody AdjustAllocationRequest req) {
        return allocations.adjustSuggestionView(id, req.targetHoldId(), req.skip(),
                req.shelfLibraryCode(), req.staff(), req.reason());
    }

    @PostMapping("/allocations/{id}/confirm")
    @Operation(summary = "确认并执行单册分配：本地直架待取或生成跨馆调拨，通知读者预计取书窗口")
    public CopyAllocationView confirm(@PathVariable Long id, @Valid @RequestBody StaffDecisionRequest req) {
        return allocations.confirmAllocationView(id, req.staff());
    }

    @PostMapping("/acquisitions/{id}/confirm")
    @Operation(summary = "一键确认执行批次内全部建议（未手动调整的采纳系统建议）")
    public AcquisitionView confirmAll(@PathVariable Long id, @Valid @RequestBody StaffDecisionRequest req) {
        return allocations.confirmAllView(id, req.staff());
    }

    @PostMapping("/acquisitions/{id}/cancel")
    @Operation(summary = "在任何复本执行前取消批次，复本统一在收货馆上架可借（原因留痕）")
    public AcquisitionView cancel(@PathVariable Long id, @Valid @RequestBody CancelAcquisitionRequest req) {
        return allocations.cancelView(id, req.staff(), req.reason());
    }

    // ---------------- 暂停 / 解除续借 ----------------

    @PostMapping("/bibs/{bibId}/renewal-freeze")
    @Operation(summary = "对热门书暂停续借：冻结期间该书在借复本续借资格校验一律拒绝")
    public RenewalFreezeView freeze(@PathVariable Long bibId, @Valid @RequestBody FreezeRenewalRequest req) {
        if (!bibId.equals(req.bibId())) {
            throw ApiException.unprocessable("路径书目与请求体 bibId 不一致");
        }
        return allocations.freezeView(bibId, req.staff(), req.reason());
    }

    @PostMapping("/bibs/{bibId}/renewal-freeze/release")
    @Operation(summary = "解除热门书暂停续借（如队列已消化）")
    public RenewalFreezeView release(@PathVariable Long bibId, @Valid @RequestBody ReleaseFreezeRequest req) {
        return allocations.releaseView(bibId, req.staff(), req.reason());
    }

    @GetMapping("/renewal-freezes")
    @Operation(summary = "续借冻结记录（默认只看生效中，activeOnly=false 含历史）")
    public List<RenewalFreezeView> freezes(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return allocations.freezeViews(activeOnly);
    }

    // ---------------- 馆藏流转记录 ----------------

    @GetMapping("/flow-logs")
    @Operation(summary = "馆藏流转记录：采购到馆/系统建议/馆员改派原因/执行/暂停续借，可按书目过滤")
    public List<FlowLogView> flowLogs(@RequestParam(required = false) Long bibId,
                                      @RequestParam(defaultValue = "100") int limit) {
        return allocations.flowLogViews(bibId, Math.max(1, Math.min(500, limit)));
    }
}
