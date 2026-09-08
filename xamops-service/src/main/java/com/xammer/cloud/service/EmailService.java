package com.xammer.cloud.service;

import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:notifications@xamops.com}")
    private String fromEmail;

    public void sendEmail(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        sendHtmlEmail(to, subject, htmlBody, java.util.Map.of());
    }

    /**
     * As {@link #sendHtmlEmail(String, String, String)}, with extra RFC 5322 headers.
     *
     * <p>Exists for the acknowledgement sent when a mail becomes a ticket, which needs two
     * things a plain send cannot express:
     *
     * <ul>
     *   <li>{@code In-Reply-To} / {@code References} naming the customer's original message, so
     *       the acknowledgement lands inside their thread and their next reply carries that
     *       Message-ID onward — which is how ingestion recognises it as belonging to the same
     *       ticket.</li>
     *   <li>{@code Auto-Submitted: auto-generated} (RFC 3834), so the recipient's vacation
     *       responder does not answer it and start a loop.</li>
     * </ul>
     */
    public void sendHtmlEmail(String to, String subject, String htmlBody,
                              java.util.Map<String, String> headers) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "XamOps");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true indicates this is HTML
            for (var header : headers.entrySet()) {
                if (header.getValue() != null && !header.getValue().isBlank()) {
                    message.setHeader(header.getKey(), header.getValue());
                }
            }
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send HTML email", e);
        }
    }
}