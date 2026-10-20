package com.bit.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public boolean sendEmail(String toEmail, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("【衣览无余】邮箱验证码");
            message.setText("尊敬的用户，您好！\n\n" +
                    "您的验证码是：" + code + "\n\n" +
                    "验证码有效期5分钟。"
            );
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("验证码发送失败，收件人{}", toEmail);
            return false;
        }
    }

}
