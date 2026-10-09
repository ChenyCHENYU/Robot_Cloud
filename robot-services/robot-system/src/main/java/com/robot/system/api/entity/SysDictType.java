package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典类型（全局表）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_type")
public class SysDictType extends BaseEntity {

    /** 类型编码（唯一，小写下划线）。 */
    private String typeCode;

    private String typeName;

    private String remark;

    /** 类型停用时，全部子项不向业务接口供给。 */
    private Integer statusFlag = 1;
}
