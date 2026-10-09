package com.robot.system.api.dto.menu;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import lombok.Data;

/** 菜单写入白名单；治理字段仅由服务端生成，更新必须提交列表返回的 revision。 */
@Data
public class MenuSaveRequest {
    @NotBlank @Size(max = 60) @JsonAlias("menuName")
    private String name;
    @NotBlank @Pattern(regexp = "directory|menu") @JsonAlias("menuType")
    private String type;
    @Size(max = 64) private String parentId;
    @Size(max = 200) private String path;
    @Size(max = 200) private String component;
    @Size(max = 200) private String redirect;
    @Size(max = 100) @JsonAlias("permCode") private String permission;
    @Size(max = 100) private String icon;
    @Min(0) @Max(9999) @JsonAlias("sortNo") private Integer sort;
    @Min(0) @Max(1) @JsonAlias("statusFlag") private Integer status;
    @Min(0) @Max(1) private Integer hidden;
    private Boolean keepAlive;
    @Size(max = 255) private String remark;
    @Min(0) private Integer revision;
    @Min(0) @Max(1) private Integer visibleFlag;
    @Min(0) @Max(1) private Integer keepAliveFlag;

    // 表单绑定不使用 Jackson alias，保留旧属性 setter。
    public void setMenuName(String value) { name = value; }
    public void setMenuType(String value) { type = value; }
    public void setPermCode(String value) { permission = value; }
    public void setSortNo(Integer value) { sort = value; }
    public void setStatusFlag(Integer value) { status = value; }

    @JsonIgnore @AssertTrue(message = "hidden/visibleFlag或keepAlive/keepAliveFlag存在冲突")
    public boolean isFlagsConsistent() {
        return (hidden == null || visibleFlag == null || hidden.equals(1 - visibleFlag))
                && (keepAlive == null || keepAliveFlag == null || keepAlive.equals(keepAliveFlag == 1));
    }

    public int effectiveVisibleFlag() {
        return hidden != null ? 1 - hidden : visibleFlag == null ? 1 : visibleFlag;
    }
    public int effectiveKeepAliveFlag() {
        return keepAlive != null ? keepAlive ? 1 : 0 : keepAliveFlag == null ? 0 : keepAliveFlag;
    }
}
