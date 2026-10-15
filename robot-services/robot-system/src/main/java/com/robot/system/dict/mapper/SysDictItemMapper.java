package com.robot.system.dict.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.system.api.entity.SysDictItem;
import org.apache.ibatis.annotations.Mapper;

/** 全局字典项持久化接口，沿用该聚合的有效标记和作用域规则。 */
@Mapper
public interface SysDictItemMapper extends BaseMapper<SysDictItem> {}
