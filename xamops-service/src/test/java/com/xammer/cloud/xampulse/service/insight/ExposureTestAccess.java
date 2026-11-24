package com.xammer.cloud.xampulse.service.insight;

import java.util.List;
import java.util.Map;

/** Test access to the exposure service's row parsing from other packages, as sorted "protocol|port|bind|address". */
public final class ExposureTestAccess {

    private ExposureTestAccess() {}

    public static List<String> latest(List<Map<String, Object>> rows) {
        return ExposureService.latest(rows).stream().map(l -> l.protocol() + "|" + l.port() + "|" + l.bind() + "|" + l.address()).sorted().toList();
    }
}
