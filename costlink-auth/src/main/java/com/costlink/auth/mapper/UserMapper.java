package com.costlink.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.costlink.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
