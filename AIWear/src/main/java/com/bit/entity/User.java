package com.bit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("Users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userName;

    private String email;

    @TableField("password_hash")
    private String passwordHash;
}
