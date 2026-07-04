package com.costlink.reimbursement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ReimbursementCreateRequest {
    @NotBlank(message = "报销事由不能为空")
    private String title;

    @NotBlank(message = "费用类型不能为空")
    private String expenseType;

    private String remark;

    @NotEmpty(message = "费用明细不能为空")
    private List<ItemDTO> items;

    @Data
    public static class ItemDTO {
        @NotBlank(message = "费用科目不能为空")
        private String category;
        @NotNull(message = "金额不能为空")
        private BigDecimal amount;
        private LocalDate receiptDate;
        private String remark;
        private Long attachmentId;
    }
}
