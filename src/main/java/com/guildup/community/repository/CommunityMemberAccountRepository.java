package com.guildup.community.repository;

import com.guildup.community.domain.CommunityMemberAccount;
import org.springframework.data.jpa.repository.JpaRepository;

/** 클랜원과 외부 서비스 계정의 연결을 저장하고 조회한다. */
public interface CommunityMemberAccountRepository extends JpaRepository<CommunityMemberAccount, Long> {
}
