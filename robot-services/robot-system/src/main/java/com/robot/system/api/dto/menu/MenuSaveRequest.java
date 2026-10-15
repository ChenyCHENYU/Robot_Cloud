package com.robot.system.api.dto.menu;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 菜单写入白名单；治理字段仅由服务端生成，更新必须提交列表返回的 revision。 */
@Data
public class MenuSaveRequest {
    @NotBlank
    @Size(max = 60)
    @JsonAlias("menuName")
    private String name;

    @NotBlank
    @Pattern(regexp = "directory|menu")
    @JsonAlias("menuType")
    private String type;

    @Size(max = 64)
    private String parentId;

    @Size(max = 200)
    private String path;

    @Size(max = 200)
    private String component;

    @Size(max = 200)
    private String redirect;

    @Size(max = 100)
    @JsonAlias("permCode")
    private String permission;

    @Size(max = 100)
    private String icon;

    @Min(0)
    @Max(9999)
    @JsonAlias("sortNo")
    private Integer sort;

    @Min(0)
    @Max(1)
    @JsonAlias("statusFlag")
    private Integer status;

    @Min(0)
    @Max(1)
    private Integer hidden;

    private Boolean keepAlive;

    @Size(max = 255)
    private String remark;

    @Min(0)
    private Integer revision;

    @Min(0)
    @Max(1)
    private Integer visibleFlag;

    @Min(0)
    @Max(1)
    private Integer keepAliveFlag;

    // 表单绑定不使用 Jackson alias，保留旧属性 setter。
    /**
     * 接收菜单显示名，兼容旧menuName字段。
     *
     * @param value 旧绑定字段的标量值
     */
    public void setMenuName(String value) {
        name = value;
    }

    /**
     * 接收菜单种类，兼容旧menuType字段。
     *
     * @param value 旧绑定字段的标量值
     */
    public void setMenuType(String value) {
        type = value;
    }

    /**
     * 接收精确权限码，兼容旧permCode字段。
     *
     * @param value 旧绑定字段的标量值
     */
    public void setPermCode(String value) {
        permission = value;
    }

    /**
     * 接收排序号，兼容旧sortNo字段。
     *
     * @param value 旧绑定字段的标量值
     */
    public void setSortNo(Integer value) {
        sort = value;
    }

    /**
     * 接收启停状态，兼容旧statusFlag字段。
     *
     * @param value 旧绑定字段的标量值
     */
    public void setStatusFlag(Integer value) {
        status = value;
    }

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 校验是否通过
     */
    @JsonIgnore
    @AssertTrue(message = "hidden/visibleFlag或keepAlive/keepAliveFlag存在冲突")
    public boolean isFlagsConsistent() {
        return (hidden == null || visibleFlag == null || hidden.equals(1 - visibleFlag))
                && (keepAlive == null
                        || keepAliveFlag == null
                        || keepAlive.equals(keepAliveFlag == 1));
    }

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public int effectiveVisibleFlag() {
        return hidden != null ? 1 - hidden : visibleFlag == null ? 1 : visibleFlag;
    }

    /**
     * 创建或返回该类型的明确协作结果。
     *
     * @return 当前事实派生的结果
     */
    public int effectiveKeepAliveFlag() {
        return keepAlive != null ? keepAlive ? 1 : 0 : keepAliveFlag == null ? 0 : keepAliveFlag;
    }
}
