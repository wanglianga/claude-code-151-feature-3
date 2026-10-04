package com.example.library;

import com.example.library.service.AppClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REST 层冒烟：检索、预约全链路、参数校验、管理端、OpenAPI/Swagger。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebApiTests {

    @Autowired MockMvc mvc;
    @Autowired AppClock clock;

    @Test
    void catalogSearchAndSwaggerAreAvailable() throws Exception {
        mvc.perform(get("/api/catalog/search")
                        .param("keyword", "三体")
                        .param("pickupLibraryCode", "WEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("三体"))
                .andExpect(jsonPath("$[0].libraries[?(@.code=='EAST')].transferable")
                        .value(org.hamcrest.Matchers.hasItem(true)));

        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void holdToCheckoutFlowOverHttp_rulesAndStats() throws Exception {
        clock.setFixed(Instant.parse("2026-10-03T08:00:00Z"));

        // 预约（参数缺失 → 400）
        mvc.perform(post("/api/holds/bibs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // R001 从城东跨馆预约到城西
        mvc.perform(post("/api/holds/bibs/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readerCardNo":"R001","pickupLibraryCode":"WEST"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.itemBarcode").exists());

        // 物流出库 → 到馆
        var pending = mvc.perform(get("/api/transfers/tasks/pending")).andReturn();
        Number taskIdNum = com.jayway.jsonpath.JsonPath.read(pending.getResponse().getContentAsString(), "$[0].id");
        Long taskId = taskIdNum.longValue();

        mvc.perform(post("/api/transfers/pickups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskIds\":[" + taskId + "],\"staffName\":\"物流员小赵\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").exists());

        var afterPickup = mvc.perform(get("/api/transfers/tasks/" + taskId)).andReturn();
        String packageNo = com.jayway.jsonpath.JsonPath.read(
                afterPickup.getResponse().getContentAsString(), "$.packageNo");

        mvc.perform(post("/api/transfers/arrivals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageNo\":\"" + packageNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));

        // 读者通知与罚款账单接口
        mvc.perform(get("/api/notifications/readers/R001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("READY"));

        mvc.perform(get("/api/fines/readers/R001")).andExpect(status().isOk());

        // 管理端
        mvc.perform(get("/api/admin/dashboard").param("days", "7")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/batches")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].packages").value(1));
        mvc.perform(get("/api/admin/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.['fine.daily']").value(0.5));
    }

    @Test
    void hotBibDemandAdviceAndAllocationFlowOverHttp() throws Exception {
        clock.setFixed(Instant.parse("2026-10-03T08:00:00Z"));

        // 需求建议列表可访问（新规则参数存在）
        mvc.perform(get("/api/admin/demand/advice")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.bibId==1)].title").value(
                        org.hamcrest.Matchers.hasItem("三体")));
        mvc.perform(get("/api/admin/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.['acquisition.waitRatio']").value(2.0))
                .andExpect(jsonPath("$.['allocation.balanceWeight']").value(0.5));

        // 缺少必填字段 → 400
        mvc.perform(post("/api/admin/acquisitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bibId\":1,\"copyCount\":1}"))
                .andExpect(status().isBadRequest());

        // 登记新购复本到馆 → 201，返回逐册建议
        mvc.perform(post("/api/admin/acquisitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bibId":1,"copyCount":1,"receivingLibraryCode":"CENTRAL",
                                 "staff":"采购馆员小冯","note":"热门加购"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.allocations[0].status").value("SUGGESTED"))
                .andExpect(jsonPath("$.allocations[0].itemBarcode").exists());

        Number acqIdNum = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/admin/acquisitions").param("bibId", "1")).andReturn()
                        .getResponse().getContentAsString(), "$[0].id");
        Long acqId = acqIdNum.longValue();
        Number allocationIdNum = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/admin/acquisitions/" + acqId)).andReturn()
                        .getResponse().getContentAsString(), "$.allocations[0].id");
        Long allocationId = allocationIdNum.longValue();

        // 手动改派缺原因 → 400
        mvc.perform(post("/api/admin/allocations/" + allocationId + "/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staff\":\"王馆长\",\"skip\":true}"))
                .andExpect(status().isBadRequest());

        // 暂停续借 + 流转记录查询
        mvc.perform(post("/api/admin/bibs/2/renewal-freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bibId\":2,\"staff\":\"王馆长\",\"reason\":\"红楼梦排队积压\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        // 冻结书目上的在借：续借资格接口在事务内读懒关联，返回 200 且明确拒绝（回归懒加载 500）
        var checkout = mvc.perform(post("/api/loans/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"readerCardNo":"R001","itemBarcode":"H-0001","libraryCode":"CENTRAL"}
                                """)).andExpect(status().isCreated()).andReturn();
        Number hongLouLoanId = com.jayway.jsonpath.JsonPath.read(
                checkout.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/loans/" + hongLouLoanId.longValue() + "/renew-eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewable").value(false))
                .andExpect(jsonPath("$.reason").value(
                        org.hamcrest.Matchers.containsString("暂停续借")));
        mvc.perform(get("/api/admin/flow-logs").param("bibId", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("RENEW_FREEZE"));
        mvc.perform(get("/api/admin/renewal-freezes")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bibId").value(2));
    }
}
