package com.robot.system.api.vo.role;

import com.robot.system.api.entity.SysRole;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色视图。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Data
@Builder
public class RoleVO {

    private String id;

    private Integer revision;

    private String roleName;

    private String roleCode;

    private String dataScope;

    private Integer sortNo;

    private Integer status;

    private Integer statusFlag;

    private String remark;

    private LocalDateTime createDateTime;

    private LocalDateTime updateDateTime;

    /** 已授权菜单 ID 集合（详情场景）。 */
    private List<String> menuIds;

    private List<String> buttonIds;

    private List<String> permissionIds;

    /** Robot Admin只读别名；保留原字段便于已有客户端过渡。 */
    public String getName() {
        return roleName;
    }

    public String getCode() {
        return roleCode;
    }

    public Integer getSort() {
        return sortNo;
    }

    public String getDescription() {
        return remark;
    }

    public LocalDateTime getCreateTime() {
        return createDateTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateDateTime;
    }

    public static RoleVO from(SysRole role) {
        return RoleVO.builder()
                .id(role.getId())
                .revision(role.getRevision())
                .roleName(role.getRoleName())
                .roleCode(role.getRoleCode())
                .dataScope(role.getDataScope())
                .sortNo(role.getSortNo())
                .status(role.getStatusFlag())
                .statusFlag(role.getStatusFlag())
                .remark(role.getRemark())
                .createDateTime(role.getCreateDateTime())
                .updateDateTime(role.getUpdateDateTime())
                .build();
    }
}
