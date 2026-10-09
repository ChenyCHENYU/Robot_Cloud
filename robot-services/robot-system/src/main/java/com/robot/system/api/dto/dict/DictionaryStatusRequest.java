package com.robot.system.api.dto.dict;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DictionaryStatusRequest(@NotNull @Min(0) @Max(1) Integer status,
                                      @NotNull @Min(0) Integer revision) { }
