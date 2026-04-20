package com.learning.account.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮箱服务
 */
@Service
public class EmailService {
    
    private final JavaMailSender mailSender;
    
    @Value("${spring.mail.username}")
    private String fromEmail;
    
    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    
    /**
     * 发送验证码邮件
     */
    public boolean sendVerificationCode(String toEmail, String verificationCode) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("验证码");
            message.setText("您的验证码是: " + verificationCode + "\n\n请勿将验证码泄露给他人。");
            
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            System.err.println("发送邮件失败: " + e.getMessage());
            return false;
        }
    }
}
