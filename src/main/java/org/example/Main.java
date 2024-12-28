package org.example;

import io.github.cdimascio.dotenv.Dotenv;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.example.HaircutBot.adminIds;

public class Main {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .filename(".env")
                .load();
        String botUsername = dotenv.get("BOT_USERNAME");
        String botToken = dotenv.get("BOT_TOKEN");
        String adminIdsStr = dotenv.get("ADMIN_IDS");
        adminIds = Arrays.stream(adminIdsStr.split(","))
                .map(Long::parseLong)
                .collect(Collectors.toSet());
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new HaircutBot(botUsername, botToken, adminIds));
            System.out.println("Бот запущен!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
