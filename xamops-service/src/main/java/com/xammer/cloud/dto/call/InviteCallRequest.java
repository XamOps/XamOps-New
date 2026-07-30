package com.xammer.cloud.dto.call;

import java.util.List;

/**
 * Request to pull additional users into a live call. The server re-checks that
 * every id is within the caller's allowed set before ringing anyone.
 */
public class InviteCallRequest {

    private List<Long> userIds;

    public List<Long> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<Long> userIds) {
        this.userIds = userIds;
    }
}
