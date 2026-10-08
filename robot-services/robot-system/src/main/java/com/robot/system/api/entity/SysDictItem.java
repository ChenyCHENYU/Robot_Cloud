package com.robot.system.api.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.robot.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 字典项（全局表）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_item")
public class SysDictItem extends BaseEntity {

    private String typeId;

    private String itemLabel;

    private String itemValue;

    private Integer sortNo;

    /** 1=启用，0=停用。 */
    private Integer statusFlag;
}
