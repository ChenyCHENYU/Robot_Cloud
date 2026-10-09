package com.robot.system.api.vo.user;

import com.robot.system.api.entity.SysUser;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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

    private LocalDateTime createDateTime;

    private LocalDateTime updateDateTime;

    /** 所属角色名称集合（展示用）。 */
    private List<String> roleNames;

    public String getDeptId() {
        return departmentId;
    }

    public LocalDateTime getCreateTime() {
        return createDateTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateDateTime;
    }

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
                .createDateTime(user.getCreateDateTime())
                .updateDateTime(user.getUpdateDateTime())
                .roleNames(roleNames)
                .build();
    }
}
