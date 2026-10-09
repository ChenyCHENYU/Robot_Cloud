package com.robot.system.api.dto.dict;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 字典类型/项写入白名单；编码、标签与存储值各自保存。 */
@Data
public class DictionarySaveRequest {
    @NotBlank
    @Pattern(regexp = "type|item")
    private String type = "type";

    @Size(max = 64)
    private String parentId;

    @Size(max = 60)
    private String code;

    @Size(max = 60)
    private String typeCode;

    @Size(max = 60)
    private String name;

    @Size(max = 60)
    private String label;

    @Size(max = 60)
    private String dictLabel;

    @Size(max = 60)
    private String value;

    @Size(max = 60)
    private String dictValue;

    @Size(max = 255)
    private String remark;

    @Min(0)
    @Max(9999)
    private Integer sort;

    @Min(0)
    @Max(1)
    private Integer status;

    @Min(0)
    private Integer revision;

    @JsonIgnore
    @AssertTrue(message = "字典名称、编码、所属类型或存储值不合法，兼容字段不得冲突")
    public boolean isDefinitionValid() {
        if ("type".equals(type)) {
            return validTypeDefinition();
        }
        return "item".equals(type) && validItemDefinition();
    }

    private boolean validTypeDefinition() {
        return nonblank(name) && nonblank(effectiveCode())
                && effectiveCode().matches("[a-zA-Z][a-zA-Z0-9_.:-]*")
                && consistent(code, typeCode);
    }

    private boolean validItemDefinition() {
        return nonblank(parentId) && nonblank(effectiveLabel())
                && nonblank(effectiveValue()) && nonblank(effectiveCode())
                && consistent(name, label) && consistent(name, dictLabel)
                && consistent(label, dictLabel) && consistent(value, dictValue);
    }
    public String effectiveLabel() {
        return first(dictLabel, label, name);
    }

    public String effectiveValue() {
        return first(dictValue, value);
    }

    public String effectiveCode() {
        return "type".equals(type) ? first(typeCode, code) : first(code, effectiveValue());
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }
    private static boolean consistent(String left, String right) {
        return !nonblank(left) || !nonblank(right) || left.equals(right);
    }
    private static String first(String... values) {
        for (String value : values) {
            if (nonblank(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
