package com.robot.system.api.dto.user;

import com.robot.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/** 用户分页查询（GET /sys/users 查询参数，字段与前端 UserListParams 对齐）。 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserPageQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：匹配用户名/昵称/邮箱。 */
    private String keyword;

    /** 状态：1=启用，0=停用。 */
    private Integer status;

    /** 部门过滤。 */
    private String deptId;
}
