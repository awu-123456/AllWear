package com.bit.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EditImageRequest {

    @NotBlank(message = "图片不能为空")
    private String image;

    @NotBlank(message = "编辑图片的指令不能为空")
    private String instruction;
}
