package com.guildup.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Objects;

/**
 * 커뮤니티에 등록된 실제 클랜원을 나타내는 JPA 엔티티다.
 * 외부 서비스 계정은 CommunityMemberAccount에서 별도로 관리한다.
 */
@Entity
@Table(name = "community_members")
public class CommunityMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Community community;

    @Column(nullable = false)
    private String nickname;

    protected CommunityMember() {
    }

    /** 클랜원을 닉네임으로 등록한다. */
    public CommunityMember(Community community, String nickname) {
        this.community = community;
        this.nickname = nickname;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    /** 닉네임이 실제로 달라졌을 때만 수정하고 변경 여부를 반환한다. */
    public boolean updateNickname(String nickname) {
        if (Objects.equals(this.nickname, nickname)) {
            return false;
        }

        this.nickname = nickname;
        return true;
    }
}
