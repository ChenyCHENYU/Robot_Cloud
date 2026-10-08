package com.robot.system.api.dto.role;

import com.robot.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/** 角色分页查询（字段与前端 RoleListParams 对齐）。 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RolePageQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：匹配角色名/编码。 */
    private String keyword;

    /** 状态：1=启用，0=停用。 */
    private Integer status;
}
