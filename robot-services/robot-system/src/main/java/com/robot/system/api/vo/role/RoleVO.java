package com.robot.system.api.vo.role;

import com.robot.system.api.entity.SysRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 角色视图。 */
@Data
@Builder
public class RoleVO {

    private String id;

    private String roleName;

    private String roleCode;

    private String dataScope;

    private Integer sortNo;

    private Integer status;

    private Integer statusFlag;

    private String remark;

    private LocalDateTime createDateTime;

    /** 已授权菜单 ID 集合（详情场景）。 */
    private List<String> menuIds;

    public static RoleVO from(SysRole role) {
        return RoleVO.builder()
                .id(role.getId())
                .roleName(role.getRoleName())
                .roleCode(role.getRoleCode())
                .dataScope(role.getDataScope())
                .sortNo(role.getSortNo())
                .status(role.getStatusFlag())
                .statusFlag(role.getStatusFlag())
                .remark(role.getRemark())
                .createDateTime(role.getCreateDateTime())
                .build();
    }
}
