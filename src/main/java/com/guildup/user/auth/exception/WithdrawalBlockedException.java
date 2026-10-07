package com.guildup.user.auth.exception;

import com.guildup.user.auth.service.AccountWithdrawalService.OwnedCommunity;
import java.util.List;

public class WithdrawalBlockedException extends RuntimeException {
    private final List<OwnedCommunity> communities;
    public WithdrawalBlockedException(List<OwnedCommunity> communities) {
        super("운영 중인 커뮤니티의 소유권을 먼저 정리해주세요.");
        this.communities = List.copyOf(communities);
    }
    public List<OwnedCommunity> getCommunities() { return communities; }
}
