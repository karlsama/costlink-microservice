package com.costlink.budget.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.costlink.budget.entity.BudgetLine;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BudgetLineMapper extends BaseMapper<BudgetLine> {
}
