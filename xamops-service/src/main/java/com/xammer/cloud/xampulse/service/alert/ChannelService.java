package com.xammer.cloud.xampulse.service.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpChannel;
import com.xammer.cloud.xampulse.dto.XpChannelConfig;
import com.xammer.cloud.xampulse.dto.XpChannelInput;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpChannelRepository;
import com.xammer.cloud.xampulse.service.alert.channel.ChannelConfig;
import com.xammer.cloud.xampulse.service.alert.channel.Notification;
import com.xammer.cloud.xampulse.service.alert.channel.NotificationChannel;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Notification channels. The destination (address list or webhook URL) is a secret: it is stored encrypted
 * ({@code config_enc} via the application's encrypted-string converter) and only a masked summary ever leaves the server.
 */
@Service("xpChannelService")
public class ChannelService {

    static final Set<String> KINDS = Set.of("EMAIL", "SLACK", "TEAMS", "WEBHOOK");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]{1,64}@[^@\\s,;]{1,255}$");

    private final XpChannelRepository repo;
    private final XamPulseAccessGuard guard;
    private final ObjectMapper mapper;
    private final OutboundUrlGuard urlGuard;
    private final Map<String, NotificationChannel> senders;

    public ChannelService(XpChannelRepository repo, XamPulseAccessGuard guard, ObjectMapper objectMapper,
                          OutboundUrlGuard urlGuard, List<NotificationChannel> senders) {
        this.repo = repo;
        this.guard = guard;
        this.mapper = objectMapper;
        this.urlGuard = urlGuard;
        this.senders = senders.stream().collect(Collectors.toMap(NotificationChannel::kind, Function.identity()));
    }

    public List<com.xammer.cloud.xampulse.dto.XpChannel> list(ClientUserDetails user, Long accountId) {
        guard.require(user, accountId);
        return repo.findByCloudAccountId(accountId).stream().map(this::toDto).toList();
    }

    public com.xammer.cloud.xampulse.dto.XpChannel create(ClientUserDetails user, XpChannelInput in) {
        guard.require(user, in.accountId());
        ChannelConfig cfg = validate(in.kind(), in.config(), null);
        XpChannel e = new XpChannel();
        e.setCloudAccountId(in.accountId());
        e.setKind(in.kind());
        e.setName(in.name());
        e.setConfigEnc(write(cfg));
        return toDto(repo.save(e));
    }

    public com.xammer.cloud.xampulse.dto.XpChannel update(ClientUserDetails user, Long id, XpChannelInput in) {
        XpChannel e = find(user, id);
        if (!e.getKind().equals(in.kind())) {
            throw XamPulseException.invalid("A channel's type cannot be changed.");
        }
        ChannelConfig existing = read(e.getConfigEnc());
        e.setName(in.name());
        e.setConfigEnc(write(validate(in.kind(), in.config(), existing)));
        return toDto(repo.save(e));
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(ClientUserDetails user, Long id) {
        XpChannel e = find(user, id);
        repo.deleteByIdAndCloudAccountId(e.getId(), e.getCloudAccountId());
    }

    /** Sends a clearly-labelled test message and reports failure to the caller. */
    public void test(ClientUserDetails user, Long id) {
        XpChannel e = find(user, id);
        try {
            sender(e.getKind()).send(read(e.getConfigEnc()), new Notification("TEST", "XamPulse test notification",
                    "This is a test message from XamPulse. No action is needed.", "LOW", e.getCloudAccountId(), null, null, null, Instant.now()));
        } catch (Exception ex) {
            throw new XamPulseException("XP_INVALID_REQUEST", 400, "The test message could not be delivered: " + shortReason(ex));
        }
    }

    /** Internal: decrypt a channel for sending. Caller has already established the account. */
    public ChannelConfig load(Long channelId, Long accountId) {
        return repo.findByIdAndCloudAccountId(channelId, accountId).map(c -> read(c.getConfigEnc())).orElse(null);
    }

    public String kindOf(Long channelId, Long accountId) {
        return repo.findByIdAndCloudAccountId(channelId, accountId).map(XpChannel::getKind).orElse(null);
    }

    public NotificationChannel sender(String kind) {
        NotificationChannel s = senders.get(kind);
        if (s == null) {
            throw XamPulseException.invalid("Unsupported channel type.");
        }
        return s;
    }

    private XpChannel find(ClientUserDetails user, Long id) {
        List<Long> accessible = guard.accessibleAwsAccounts(user).stream().map(a -> a.getId()).toList();
        return repo.findByIdAndCloudAccountIdIn(id, accessible).orElseThrow(XamPulseException::notFound);
    }

    ChannelConfig validate(String kind, XpChannelConfig in, ChannelConfig existing) {
        if (kind == null || !KINDS.contains(kind)) {
            throw XamPulseException.invalid("Unsupported channel type.");
        }
        if ("EMAIL".equals(kind)) {
            List<String> to = in.to() != null && !in.to().isEmpty() ? in.to() : (existing == null ? List.of() : existing.to());
            if (to == null || to.isEmpty() || to.size() > 10) {
                throw XamPulseException.invalid("Provide 1 to 10 email addresses.");
            }
            for (String a : to) {
                if (a == null || !EMAIL.matcher(a.trim()).matches()) {
                    throw XamPulseException.invalid("One of the email addresses is not valid.");
                }
            }
            return new ChannelConfig(to.stream().map(String::trim).toList(), null);
        }
        String url = in.url() != null && !in.url().isBlank() ? in.url().trim() : (existing == null ? null : existing.url());
        if (url == null) {
            throw XamPulseException.invalid("A URL is required.");
        }
        urlGuard.validate(url);
        return new ChannelConfig(null, url);
    }

    private String write(ChannelConfig cfg) {
        try {
            return mapper.writeValueAsString(Map.of("to", cfg.to() == null ? List.of() : cfg.to(), "url", cfg.url() == null ? "" : cfg.url()));
        } catch (JsonProcessingException e) {
            throw XamPulseException.invalid("Invalid channel settings.");
        }
    }

    @SuppressWarnings("unchecked")
    private ChannelConfig read(String json) {
        try {
            Map<String, Object> m = mapper.readValue(json, Map.class);
            List<String> to = new ArrayList<>();
            Object t = m.get("to");
            if (t instanceof List<?> l) {
                l.forEach(x -> to.add(String.valueOf(x)));
            }
            Object u = m.get("url");
            return new ChannelConfig(to, u == null || String.valueOf(u).isEmpty() ? null : String.valueOf(u));
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("Channel settings could not be read");
        }
    }

    private com.xammer.cloud.xampulse.dto.XpChannel toDto(XpChannel c) {
        return new com.xammer.cloud.xampulse.dto.XpChannel(c.getId(), c.getCloudAccountId(), c.getKind(), c.getName(), true,
                mask(c.getKind(), read(c.getConfigEnc())));
    }

    /** "hooks.slack.com/…/••••" or "a***@example.com (+1)". */
    static String mask(String kind, ChannelConfig cfg) {
        if ("EMAIL".equals(kind)) {
            String first = cfg.to().isEmpty() ? "" : cfg.to().get(0);
            int at = first.indexOf('@');
            String masked = at > 0 ? first.charAt(0) + "***" + first.substring(at) : "***";
            return cfg.to().size() > 1 ? masked + " (+" + (cfg.to().size() - 1) + ")" : masked;
        }
        try {
            return URI.create(cfg.url()).getHost() + "/…/••••";
        } catch (RuntimeException e) {
            return "••••";
        }
    }

    private static String shortReason(Exception e) {
        if (e instanceof XamPulseException x) {
            return x.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
