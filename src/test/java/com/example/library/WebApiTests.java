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
        clock.reset();
    }

    @Test
    void hotBookDemandAndAcquisitionOverHttp() throws Exception {
        clock.setFixed(Instant.parse("2026-10-03T08:00:00Z"));
        try {
            // 需求调控建议（等待人数/复本/续借限制/调拨成本 → 采购或暂停续借）
            mvc.perform(get("/api/admin/hot-books/2/demand-advice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.waitingCount").isNumber())
                    .andExpect(jsonPath("$.renewLimit").isNumber())
                    .andExpect(jsonPath("$.suggestPurchase").isBoolean())
                    .andExpect(jsonPath("$.suggestSuspendRenewal").isBoolean())
                    .andExpect(jsonPath("$.reasons").isArray());

            // 新增复本分配建议预览
            mvc.perform(get("/api/admin/hot-books/1/allocation-plan")
                            .param("arrivalLibraryCode", "CENTRAL").param("copies", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignments").isArray())
                    .andExpect(jsonPath("$.branchWaits").isArray())
                    .andExpect(jsonPath("$.rules").isArray());

            // 采购到馆（当前无排队 → 上架可借）
            mvc.perform(post("/api/admin/hot-books/1/acquisitions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"arrivalLibraryCode\":\"CENTRAL\",\"copies\":1,\"staff\":\"馆员小王\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.copies[0].barcode").value("N1-0001"))
                    .andExpect(jsonPath("$.copies[0].assignmentType").value("SHELF"));

            // 馆藏流转记录
            mvc.perform(get("/api/admin/hot-books/items/N1-0001/movements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].type").value("ACQUISITION"));
            mvc.perform(get("/api/admin/hot-books/1/movements")).andExpect(status().isOk());

            // 暂停续借：无理由 → 422；有理由 → 生效
            mvc.perform(put("/api/admin/hot-books/1/renewal-suspension")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"suspended\":true,\"staff\":\"馆员小王\"}"))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(put("/api/admin/hot-books/1/renewal-suspension")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"suspended\":true,\"staff\":\"馆员小王\",\"reason\":\"预约爆满，加快流转\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.renewalSuspended").value(true));
        } finally {
            clock.reset();
        }
    }
}
