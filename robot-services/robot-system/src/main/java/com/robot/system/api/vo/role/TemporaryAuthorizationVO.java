package com.robot.system.api.vo.role;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/** 当前公司临时角色授予的管理视图，身份与窗口可核对，不暴露内部删除标记。 */
@Data
@Builder
public class TemporaryAuthorizationVO {
    private String id;
    private String userId;
    private String roleId;
    private String userNickname;
    private String roleName;
    private LocalDateTime effectFrom;
    private LocalDateTime effectTo;
    private String reason;
    private Integer statusFlag;
    private Integer revision;
}
