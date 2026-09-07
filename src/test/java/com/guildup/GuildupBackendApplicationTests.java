package com.guildup;

import com.guildup.discord.bot.DiscordBot;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:context;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class GuildupBackendApplicationTests {

    @MockitoBean
    private JDA jda;

    @MockitoBean
    private DiscordBot discordBot;

    @Test
    void contextLoads() {
    }

}
