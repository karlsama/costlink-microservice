package com.costlink.reimbursement.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalCompletedEvent {
    private Long reimbursementId;
    private String action;
    private Long instanceId;
}
