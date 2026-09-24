package com.ccr.admin.system.dto;
import jakarta.validation.constraints.*;

public record MenuSaveRequest(
    @NotNull @PositiveOrZero Long parentId,
    @NotBlank @Size(max=64) String menuName,
    @NotNull @Pattern(regexp="M|C") String menuType,
    @Size(max=128) String path,
    @Size(max=64) String icon,
    @NotNull @Min(0) @Max(9999) Integer sortNo,
    @NotNull @Pattern(regexp="SHOW|HIDE") String visible,
    @NotNull @Pattern(regexp="ENABLE|DISABLE") String status) {}
