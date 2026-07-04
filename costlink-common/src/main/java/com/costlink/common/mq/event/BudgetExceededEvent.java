package com.costlink.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 预算超支事件 — 预算服务发布，通知服务消费（告知主管预算不足）
 *
 * 重要: notifyUserIds 由预算服务在发布前填充。
 * 预算服务调 AuthClient 把 notify_roles（角色名）翻译为具体用户 ID 列表后填入此字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetExceededEvent implements Serializable {

    /** 部门ID */
    private Long departmentId;
    /** 部门名称 */
    private String departmentName;
    /** 费用科目 */
    private String category;
    /** 已使用比例（如 85.5 表示 85.5%） */
    private Double usedRate;
    /** 可用余额 */
    private BigDecimal availableAmount;
    /** 通知对象ID列表（预算服务发之前已翻译好） */
    private List<Long> notifyUserIds;
}
