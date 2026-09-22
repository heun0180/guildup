package com.guildup.bingo;

import com.guildup.bingo.domain.BingoMissionType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BingoMissionSchemaTests {

    private static final Pattern SQL_VALUE = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    @Test
    void databaseConstraintAllowsEveryMissionType() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/manual/update_bingo_mission_type_constraint.sql"));
        Set<String> allowedMissionTypes = SQL_VALUE.matcher(sql).results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());
        Set<String> applicationMissionTypes = Arrays.stream(BingoMissionType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(allowedMissionTypes).isEqualTo(applicationMissionTypes);
    }
}
