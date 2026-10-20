package com.bit.common;

import lombok.Data;

@Data
public class Result <T>{

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(String message, T data){
        Result<T> result = new Result<T>();
        result.code = 200;
        result.message = message;
        result.data = data;
        return result;
    }

    public static <T> Result<T> clientError(String message){
        Result<T> result = new Result<T>();
        result.code = 400;
        result.message = message;
        return result;
    }

    public static <T> Result<T> serverError(String message){
        Result<T> result = new Result<T>();
        result.code = 500;
        result.message = message;
        return result;
    }
}
