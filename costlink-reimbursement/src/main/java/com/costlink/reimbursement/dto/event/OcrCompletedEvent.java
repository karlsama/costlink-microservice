package com.costlink.reimbursement.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrCompletedEvent {
    private Long attachmentId;
    private Long reimbursementId;
    private BigDecimal ocrAmount;
    private String ocrResult;
}
