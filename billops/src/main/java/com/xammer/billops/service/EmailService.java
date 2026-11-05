package com.xammer.billops.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmailService {

    /**
     * A file sent with an email. The content is held in memory because JavaMail reads an
     * attachment more than once while it builds the message.
     */
    public record Attachment(String fileName, String contentType, byte[] content) {
        public Attachment {
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
        }
    }

    @Autowired
    private JavaMailSender emailSender;

    public void sendSimpleMessage(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("noreply@xammer.in");
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        emailSender.send(message);
    }

    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        sendHtmlEmail(to, subject, htmlBody, List.of());
    }

    /** As {@link #sendHtmlEmail(String, String, String)}, with files attached to the message. */
    public void sendHtmlEmail(String to, String subject, String htmlBody, List<Attachment> attachments) {
        try {
            MimeMessage message = emailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom("noreply@xammer.in");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true indicates this is HTML
            addAttachments(helper, attachments);
            emailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send HTML email", e);
        }
    }

    public void sendHtmlEmailToMultiple(String[] toAddresses, String subject, String htmlBody) {
        sendHtmlEmailToMultiple(toAddresses, subject, htmlBody, List.of());
    }

    /** As {@link #sendHtmlEmailToMultiple(String[], String, String)}, with files attached to the message. */
    public void sendHtmlEmailToMultiple(String[] toAddresses, String subject, String htmlBody,
                                        List<Attachment> attachments) {
        try {
            MimeMessage message = emailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom("noreply@xammer.in");
            helper.setTo(toAddresses);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            addAttachments(helper, attachments);
            emailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send HTML email to multiple recipients", e);
        }
    }

    private static void addAttachments(MimeMessageHelper helper, List<Attachment> attachments)
            throws MessagingException {
        for (Attachment attachment : attachments) {
            helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()),
                    attachment.contentType());
        }
    }
}
