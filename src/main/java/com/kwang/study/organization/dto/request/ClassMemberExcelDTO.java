package com.kwang.study.organization.dto.request;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 用于接收 Excel 导入班级成员的数据映射类
 */
@Data
public class ClassMemberExcelDTO {

    @ExcelProperty("用户名")
    private String username;

    @ExcelProperty("密码")
    private String password;

    @ExcelProperty("角色")
    private String role;
}