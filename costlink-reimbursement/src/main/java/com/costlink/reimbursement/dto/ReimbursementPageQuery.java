package com.costlink.reimbursement.dto;

import lombok.Data;

@Data
public class ReimbursementPageQuery {
    private int page = 1;
    private int size = 10;
    private String status;
}
