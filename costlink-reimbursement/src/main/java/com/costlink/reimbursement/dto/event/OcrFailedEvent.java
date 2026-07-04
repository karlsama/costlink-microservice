package com.costlink.reimbursement.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OcrFailedEvent {
    private Long attachmentId;
    private Long reimbursementId;
    private String errorMessage;
}
