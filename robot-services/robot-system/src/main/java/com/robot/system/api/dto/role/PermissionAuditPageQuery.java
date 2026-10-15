package com.robot.system.api.dto.role;

import com.robot.common.core.domain.PageQuery;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

/** 可检索当前公司的历史成功审计，固定稳定排序。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PermissionAuditPageQuery extends PageQuery {
    @Size(max = 50)
    private String action;

    @Size(max = 64)
    private String target;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime from;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime to;
}
