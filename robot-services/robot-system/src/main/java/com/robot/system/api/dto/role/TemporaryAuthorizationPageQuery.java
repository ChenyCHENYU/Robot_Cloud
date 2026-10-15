package com.robot.system.api.dto.role;

import com.robot.common.core.domain.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 临时记录的公司内稳定分页，可按用户、角色和撤销状态过滤。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TemporaryAuthorizationPageQuery extends PageQuery {
    @Size(max = 64)
    private String userId;

    @Size(max = 64)
    private String roleId;

    @Min(0)
    @Max(1)
    private Integer statusFlag;
}
