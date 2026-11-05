package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.dto.XpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Always present, even when XamPulse is switched off, so the frontend can tell "feature off" apart from errors.
 * {@code demoAvailable} stays false: the demo seeder is not part of this build.
 */
@RestController("xpStatusController")
@RequestMapping("/api/xamops/xampulse")
public class XamPulseStatusController {

    private final XamPulseProperties props;

    public XamPulseStatusController(XamPulseProperties props) {
        this.props = props;
    }

    @GetMapping("/status")
    public XpStatus status() {
        return new XpStatus(props.isEnabled(), false);
    }
}
