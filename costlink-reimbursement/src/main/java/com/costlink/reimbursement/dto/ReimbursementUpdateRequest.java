package com.costlink.reimbursement.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ReimbursementUpdateRequest {
    private String title;
    private String expenseType;
    private String remark;
    private List<ItemDTO> items;

    @Data
    public static class ItemDTO {
        private Long id;
        private String category;
        private BigDecimal amount;
        private LocalDate receiptDate;
        private String remark;
        private Long attachmentId;
    }
}
