package com.robot.system.api.vo.user;

import com.robot.system.api.entity.SysUser;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 用户列表/详情视图（禁止携带 passwordHash）。
 *
 * @author Robot Cloud
 * @since 1.0.0-SNAPSHOT
 */
@Data
@Builder
public class UserVO {

    private String id;

    private Integer revision;

    private Integer membershipRevision;

    private List<String> roleIds;

    private String username;

    private String nickname;

    private String email;

    private String phone;

    private String departmentId;

    private Integer status;

    /** 前端兼容字段（UserListParams 读取 status，展示层同名字段）。 */
    private Integer statusFlag;

    /** 全局账号只读状态；成员启停命令不改变此字段。 */
    private Integer accountStatus;

    private LocalDateTime createDateTime;

    private LocalDateTime updateDateTime;

    /** 所属角色名称集合（展示用）。 */
    private List<String> roleNames;

    /**
     * 返回只读快照。
     *
     * @return 当前事实派生的结果
     */
    public String getDeptId() {
        return departmentId;
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
     * @param user 当前账号事实或会话主体
     * @param roleNames 角色显示名称快照
     * @return 当前事实派生的结果
     */
    public static UserVO from(SysUser user, List<String> roleNames) {
        return UserVO.builder()
                .id(user.getId())
                .revision(user.getRevision())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .status(user.getStatusFlag())
                .statusFlag(user.getStatusFlag())
                .accountStatus(user.getStatusFlag())
                .createDateTime(user.getCreateDateTime())
                .updateDateTime(user.getUpdateDateTime())
                .roleNames(roleNames)
                .build();
    }

    /**
     * 返回公司角色ID集合。
     *
     * @return 公司角色ID集合
     */
    public List<String> getRoleIds() {
        return roleIds == null ? null : new java.util.ArrayList<>(roleIds);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setRoleIds(List<String> value) {
        roleIds = value == null ? null : new java.util.ArrayList<>(value);
    }
    /**
     * 返回角色显示名称快照。
     *
     * @return 角色显示名称快照
     */
    public List<String> getRoleNames() {
        return roleNames == null ? null : new java.util.ArrayList<>(roleNames);
    }
    /**
     * 复制输入集合以隔离调用方后续修改。
     *
     * @param value 待校验或快照复制的输入值
     */
    public void setRoleNames(List<String> value) {
        roleNames = value == null ? null : new java.util.ArrayList<>(value);
    }
    /** 定义UserVOBuilder的业务边界或值快照。 */
    public static class UserVOBuilder {
        /**
         * 复制当前公司角色绑定配置ID，允许读取配置中的停用角色。
         *
         * @param value 待校验或快照复制的输入值
         * @return 当前事实派生的结果
         */
        public UserVOBuilder roleIds(List<String> value) {
            roleIds = value == null ? null : new java.util.ArrayList<>(value);
            return this;
        }
        /**
         * 创建或返回该类型的明确协作结果。
         *
         * @param value 待校验或快照复制的输入值
         * @return 当前事实派生的结果
         */
        public UserVOBuilder roleNames(List<String> value) {
            roleNames = value == null ? null : new java.util.ArrayList<>(value);
            return this;
        }
    }
}
