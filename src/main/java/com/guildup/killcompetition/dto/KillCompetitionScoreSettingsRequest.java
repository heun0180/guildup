package com.guildup.killcompetition.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

public record KillCompetitionScoreSettingsRequest(
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer killPoint,
        Boolean placementPointEnabled,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer firstPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer secondPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer thirdPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer fourthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer fifthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer sixthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer seventhPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer eighthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer ninthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer tenthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer fourthFifthPlacePoint,
        @JsonDeserialize(using = StrictScoreIntegerDeserializer.class) Integer sixthTenthPlacePoint
) {
    public KillCompetitionScoreSettingsRequest(Integer killPoint, Boolean placementPointEnabled,
                                                Integer firstPlacePoint, Integer secondPlacePoint,
                                                Integer thirdPlacePoint, Integer fourthFifthPlacePoint,
                                                Integer sixthTenthPlacePoint) {
        this(killPoint, placementPointEnabled, firstPlacePoint, secondPlacePoint, thirdPlacePoint,
                null, null, null, null, null, null, null,
                fourthFifthPlacePoint, sixthTenthPlacePoint);
    }
}
