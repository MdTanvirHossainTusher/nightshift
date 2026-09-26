package com.nightshift.service.outbox;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.ExternalServiceException;
import com.nightshift.model.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends notification emails via Spring's {@link JavaMailSender}.
 */
@Slf4j
@Component
public class EmailSender {

    private final JavaMailSender mailSender;
    private final NightshiftProperties properties;

    public EmailSender(@org.springframework.beans.factory.annotation.Autowired(required = false) JavaMailSender mailSender,
                       NightshiftProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void send(Notification notification) {
        if (mailSender == null) {
            log.info("JavaMailSender bean is unavailable; skipping dispatch to {}", notification.getRecipient());
            return;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(properties.getNotify().getFrom());
            msg.setTo(notification.getRecipient());
            msg.setSubject(notification.getSubject());
            msg.setText(notification.getBody() != null ? notification.getBody() : "");

            log.info("Sending notification email to {} with subject: {}", notification.getRecipient(), notification.getSubject());
            mailSender.send(msg);
            log.info("Successfully sent notification email to {}", notification.getRecipient());
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", notification.getRecipient(), e.getMessage());
            throw new ExternalServiceException(
                    ErrorCodes.UPSTREAM_SERVICE_UNAVAILABLE,
                    "SMTP dispatch failed: " + e.getMessage()
            );
        }
    }
}