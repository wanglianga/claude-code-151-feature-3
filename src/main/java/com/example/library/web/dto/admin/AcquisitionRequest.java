package com.example.library.web.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 馆员登记新采购复本到馆 */
public record AcquisitionRequest(
        @NotNull Long bibId,
        @NotNull @Min(1) @Max(100) Integer copyCount,
        /** 收货/落点分馆，缺省中心馆 */
        String receivingLibraryCode,
        @NotBlank String staff,
        String note
) {}
