package com.guildup.community.dto;

import com.guildup.community.domain.RankingPeriodType;
import java.time.LocalDate;

/** endDate는 화면에 표시할 포함 종료일이며, DB 조회는 다음 날 미만으로 수행한다. */
public record RankingPeriodResponse(
        RankingPeriodType periodType, Integer year, Integer month, Integer quarter,
        LocalDate startDate, LocalDate endDate, String title, boolean current
) {}
