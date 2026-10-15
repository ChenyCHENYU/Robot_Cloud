package com.robot.system.api.vo.role;

import com.robot.system.api.entity.SysRole;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

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

    /**
     * Robot Admin只读别名；保留原字段便于已有客户端过渡。
     *
     * @return 显示名称
     */
    public String getName() {
        return roleName;
    }

    /**
     * 返回业务编码。
     *
     * @return 业务编码
     */
    public String getCode() {
        return roleCode;
    }

    /**
     * 返回排序号。
     *
     * @return 排序号
     */
    public Integer getSort() {
        return sortNo;
    }

    /**
     * 返回显示说明。
     *
     * @return 显示说明
     */
    public String getDescription() {
        return remark;
    }

    /**
     * 返回创建时间显示值。
     *
     * @return 创建时间显示值
     */
    public LocalDateTime getCreateTime() {
        return createDateTime;
    }

    /**
     * 返回只读快照。
     *
     * @return 当前事实派生的结果
     */
    public LocalDateTime getUpdateTime() {
        return updateDateTime;
    }

    /**
     * 从当前实体构造客户端只读视图。
     *
     * @param role 已校验的公司角色事实
     * @return 当前事实派生的结果
     */
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

    /**
     * 返回精确绑定的菜单ID集合。
     *
     * @return 精确绑定的菜单ID集合
     */
    public List<String> getMenuIds() {
        return menuIds == null ? null : new java.util.ArrayList<>(menuIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setMenuIds(List<String> value) {
        menuIds = value == null ? null : new java.util.ArrayList<>(value);
    }
    /**
     * 返回精确绑定的按钮ID集合。
     *
     * @return 精确绑定的按钮ID集合
     */
    public List<String> getButtonIds() {
        return buttonIds == null ? null : new java.util.ArrayList<>(buttonIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setButtonIds(List<String> value) {
        buttonIds = value == null ? null : new java.util.ArrayList<>(value);
    }
    /**
     * 返回精确授权资源ID集合。
     *
     * @return 精确授权资源ID集合
     */
    public List<String> getPermissionIds() {
        return permissionIds == null ? null : new java.util.ArrayList<>(permissionIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setPermissionIds(List<String> value) {
        permissionIds = value == null ? null : new java.util.ArrayList<>(value);
    }
    /** 定义RoleVOBuilder的业务边界或值快照。 */
    public static class RoleVOBuilder {
        /**
         * 创建或返回该类型的明确协作结果。
         *
         * @param value 待校验或快照复制的输入值
         * @return 当前事实派生的结果
         */
        public RoleVOBuilder menuIds(List<String> value) {
            menuIds = value == null ? null : new java.util.ArrayList<>(value);
            return this;
        }
        /**
         * 创建或返回该类型的明确协作结果。
         *
         * @param value 待校验或快照复制的输入值
         * @return 当前事实派生的结果
         */
        public RoleVOBuilder buttonIds(List<String> value) {
            buttonIds = value == null ? null : new java.util.ArrayList<>(value);
            return this;
        }
        /**
         * 创建或返回该类型的明确协作结果。
         *
         * @param value 待校验或快照复制的输入值
         * @return 当前事实派生的结果
         */
        public RoleVOBuilder permissionIds(List<String> value) {
            permissionIds = value == null ? null : new java.util.ArrayList<>(value);
            return this;
        }
    }
}
