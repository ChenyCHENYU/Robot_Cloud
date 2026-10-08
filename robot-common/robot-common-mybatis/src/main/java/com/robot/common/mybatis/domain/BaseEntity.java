package com.robot.common.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务表基础字段（对齐 wl-skills-bd「ID + 七个治理字段」基线的通用部分）：
 * ID / CREATE_USER_NO / CREATE_DATE_TIME / UPDATE_USER_NO /
 * UPDATE_DATE_TIME / DELETE_FLAG / REVISION。
 *
 * <p>类型约定：
 * <ul>
 *   <li>ID：String VARCHAR(64) 不透明 ID，由 {@code RobotIdGenerator} 生成；</li>
 *   <li>时间：DATETIME ↔ LocalDateTime；</li>
 *   <li>DELETE_FLAG：1=有效 / 0=删除（profile 语义，禁止反向）；</li>
 *   <li>REVISION：乐观锁版本，初始 0，禁止手工维护。</li>
 * </ul>
 *
 * <p>选型：全局表（无 COMPANY_ID 列）继承本类；租户表继承 {@link TenantEntity}。
 */
@Data
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键：不透明 String ID。 */
    @TableId
    private String id;

    /** 创建人工号。 */
    @TableField(value = "create_user_no", fill = FieldFill.INSERT)
    private String createUserNo;

    /** 创建时间。 */
    @TableField(value = "create_date_time", fill = FieldFill.INSERT)
    private LocalDateTime createDateTime;

    /** 更新人工号。 */
    @TableField(value = "update_user_no", fill = FieldFill.INSERT_UPDATE)
    private String updateUserNo;

    /** 更新时间。 */
    @TableField(value = "update_date_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateDateTime;

    /** 软删标记：1=有效，0=删除。 */
    @TableField(value = "delete_flag", fill = FieldFill.INSERT)
    private Integer deleteFlag;

    /** 乐观锁版本。 */
    @Version
    @TableField(value = "revision", fill = FieldFill.INSERT)
    private Integer revision;
}
