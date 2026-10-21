package com.bit.log;

import com.bit.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class ApiLogAspect {

    @Pointcut("@annotation(com.bit.log.ApiLog)")
    public void apiPointCut() {

    }

    @Around("apiPointCut()")
    public Object around(ProceedingJoinPoint joinPoint) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodName = method.getName();

        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();

        Object result = null;
        Throwable throwable = null;

        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            throwable = e;
            return Result.serverError(e.getMessage());
        } finally {
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("\n----API日志----");
            logBuilder.append("方法名：").append(methodName).append("\n");
            logBuilder.append("请求路径：").append(request.getRequestURI()).append("\n");
            logBuilder.append("请求方法：").append(request.getMethod()).append("\n");
            if(args != null && args.length > 0){
                logBuilder.append("请求参数：\n");
                for (int i = 0; i < args.length; i++) {
                    String paramName = paramNames[i];
                    Object arg = args[i];
                    logBuilder.append(" ").append(paramName).append("：").append(arg).append("\n");
                }
            }

            if(throwable != null){
                logBuilder.append("执行异常\n");
                logBuilder.append("异常信息：").append(throwable.getMessage()).append("\n");
            } else {
                logBuilder.append("执行成功\n");
                try {
                    logBuilder.append("响应结果：").append(result).append("\n");
                } catch (Exception e) {
                    logBuilder.append("响应结果无法序列化").append("\n");
                }
            }
            log.info(logBuilder.toString());
        }
        return result;
    }
}
