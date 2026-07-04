package com.costlink.reimbursement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.costlink.reimbursement.entity.ExpenseItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExpenseItemMapper extends BaseMapper<ExpenseItem> {
}
