package com.kwang.study.homework.dto.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DownloadDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String actualPath;
    private String fileName;
}
