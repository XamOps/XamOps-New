package com.xammer.billops.dto;

import java.util.List;

public class MeetingInviteRequest {

    private String meetTitle;
    private String meetDateTime; // "YYYY-MM-DDTHH:mm" in IST
    private String meetDuration; // minutes as string: "30", "45", "60", "90", "120"
    private String meetLink;     // Google Meet URL pasted by admin
    private List<String> recipientEmails;

    public MeetingInviteRequest() {}

    public String getMeetTitle() { return meetTitle; }
    public void setMeetTitle(String meetTitle) { this.meetTitle = meetTitle; }

    public String getMeetDateTime() { return meetDateTime; }
    public void setMeetDateTime(String meetDateTime) { this.meetDateTime = meetDateTime; }

    public String getMeetDuration() { return meetDuration; }
    public void setMeetDuration(String meetDuration) { this.meetDuration = meetDuration; }

    public String getMeetLink() { return meetLink; }
    public void setMeetLink(String meetLink) { this.meetLink = meetLink; }

    public List<String> getRecipientEmails() { return recipientEmails; }
    public void setRecipientEmails(List<String> recipientEmails) { this.recipientEmails = recipientEmails; }
}
