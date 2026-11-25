package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.dto.XpResourceFilter;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Turns a request scope into a {@link QueryScope} after authorising every account and bounding the time range. */
@Service("xpQueryScopeFactory")
public class QueryScopeFactory {

    private static final int MAX_FILTER_VALUES = 500;
    private static final int MAX_VALUE_LEN = 200;

    private final XamPulseAccessGuard guard;
    private final XamPulseProperties props;

    public QueryScopeFactory(XamPulseAccessGuard guard, XamPulseProperties props) {
        this.guard = guard;
        this.props = props;
    }

    public QueryScope build(ClientUserDetails user, XpScope scope) {
        if (scope == null) {
            throw XamPulseException.invalid("A scope is required.");
        }
        XpResourceFilter r = scope.resource();
        return build(user, scope.accountIds(), scope.from(), scope.to(), scope.regions(),
                r == null ? null : r.instanceIds(), r == null ? null : r.services(), r == null ? null : r.containers());
    }

    public QueryScope build(ClientUserDetails user, List<Long> accountIds, Instant from, Instant to) {
        return build(user, accountIds, from, to, null, null, null, null);
    }

    public QueryScope build(ClientUserDetails user, List<Long> accountIds, Instant from, Instant to,
                            List<String> regions, List<String> instances, List<String> services, List<String> containers) {
        // Authorise first: a caller who names someone else's account learns nothing from validation errors.
        List<Long> authorised = guard.requireAll(user, accountIds);
        if (from == null || to == null || !to.isAfter(from)) {
            throw XamPulseException.invalid("'to' must be after 'from'.");
        }
        if (Duration.between(from, to).toDays() > props.getLimits().getMaxRangeDays()
                || Duration.between(from, to).compareTo(Duration.ofDays(props.getLimits().getMaxRangeDays())) > 0) {
            throw XamPulseException.tooBroad(props.getLimits().getMaxRangeDays());
        }
        return new QueryScope(authorised, from, to, clean(regions), clean(instances), clean(services), clean(containers));
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw XamPulseException.invalid("Too many values in a resource filter.");
        }
        for (String v : values) {
            if (v == null || v.isBlank() || v.length() > MAX_VALUE_LEN || v.chars().anyMatch(Character::isISOControl)) {
                throw XamPulseException.invalid("Invalid value in a resource filter.");
            }
        }
        return List.copyOf(values);
    }
}
