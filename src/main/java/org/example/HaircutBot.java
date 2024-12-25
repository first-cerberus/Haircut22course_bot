package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class HaircutBot extends TelegramLongPollingBot {
    private final Map<String, Map<String, LocalDateTime>> bookings = new HashMap<>();
    private final Map<String, String> userBookings = new HashMap<>(); // Tracks which barber a user is booked with
    private final Set<Long> adminIds = Set.of(1514302273L, 818667420L, 799128809L);
    private final String botUsername;
    private final String botToken;
    private boolean isWaitingForBarber = false;
    private boolean isWaitingForDate = false;
    private boolean isWaitingForName = false;
    private boolean isWaitingForChanges = false;
    private String selectedBarber = null;

    public HaircutBot(String botUsername, String botToken) {
        this.botUsername = botUsername;
        this.botToken = botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String userName = update.getMessage().getFrom().getFirstName();
            Long userId = update.getMessage().getFrom().getId();

            switch (messageText) {
                case "/start":
                    showMainMenu(chatId);
                    break;
                case "Показати всі записи":
                    sendTextMessage(chatId, formatAllRecords());
                    break;
                case "Видалити свій запис":
                    deleteUserRecord(userName, chatId);
                    break;
                case "Записатись на стрижку":
                    showBarberSelection(chatId);
                    break;
                case "Адмін функціонал":
                    if (isAdmin(userId)) {
                        showAdminMenu(chatId);
                    } else {
                        sendTextMessage(chatId, "У вас немає доступу до цього розділу.");
                    }
                    break;
                case "Змінити час запису":
                    isWaitingForChanges = true;
                    sendTextMessage(chatId, "Введіть у форматі: Барбер Ім'я yyyy-MM-dd HH:mm");
                    break;
                case "Видалити запис користувача":
                    isWaitingForName = true;
                    sendTextMessage(chatId, "Введіть барбера та ім'я користувача для видалення (формат: Барбер Ім'я):");
                    break;
                case "В головне меню":
                    resetWaitingStates();
                    showMainMenu(chatId);
                    break;
                default:
                    if (isWaitingForBarber) {
                        selectedBarber = messageText;
                        promptForBooking(chatId);
                        isWaitingForBarber = false;
                        isWaitingForDate = true;
                    } else if (isWaitingForDate) {
                        handleUserInput(chatId, userName, messageText);
                    } else if (isWaitingForName) {
                        processDeletionRequest(chatId, messageText);
                    } else if (isWaitingForChanges) {
                        processChangeRequest(chatId, messageText);
                    } else {
                        sendTextMessage(chatId, "Невідома команда. Спробуйте ще раз.");
                    }
                    break;
            }
        }
    }

    private void resetWaitingStates() {
        isWaitingForBarber = false;
        isWaitingForDate = false;
        isWaitingForName = false;
        isWaitingForChanges = false;
        selectedBarber = null;
    }

    private void showMainMenu(long chatId) {
        ReplyKeyboardMarkup keyboardMarkup = createKeyboard(List.of(
                List.of("Записатись на стрижку", "Видалити свій запис"),
                List.of("Показати всі записи", "Адмін функціонал")
        ));
        sendKeyboardMessage(chatId, "Вітаю! Оберіть дію:", keyboardMarkup);
    }

    private void showAdminMenu(long chatId) {
        ReplyKeyboardMarkup keyboardMarkup = createKeyboard(List.of(
                List.of("Змінити час запису", "Видалити запис користувача"),
                List.of("В головне меню")
        ));
        sendKeyboardMessage(chatId, "Виберіть дію:", keyboardMarkup);
    }

    private void showBarberSelection(long chatId) {
        isWaitingForBarber = true;
        ReplyKeyboardMarkup keyboardMarkup = createKeyboard(List.of(
                List.of("Різа", "Ілля", "Артем"),
                List.of("В головне меню")
        ));
        sendKeyboardMessage(chatId, "Оберіть барбера:", keyboardMarkup);
    }

    private void promptForBooking(long chatId) {
        sendTextMessage(chatId, "Введіть дату (формат: 2024-12-21 15:00)");
    }

    private void handleUserInput(long chatId, String userName, String input) {
        try {
            if (userBookings.containsKey(userName)) {
                sendTextMessage(chatId, "Ви вже записані до барбера " + userBookings.get(userName) + ". Неможливо записатись повторно.");
                return;
            }

            LocalDateTime dateTime = validateAndParseDate(input, chatId);
            if (dateTime == null) return;

            bookings.computeIfAbsent(selectedBarber, k -> new HashMap<>()).put(userName, dateTime);
            userBookings.put(userName, selectedBarber);
            sendTextMessage(chatId, formatBookingConfirmation(dateTime));
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат дати. Спробуйте ще раз.");
        } finally {
            isWaitingForDate = false;
        }
    }

    private void deleteUserRecord(String userName, long chatId) {
        if (userBookings.containsKey(userName)) {
            String barber = userBookings.remove(userName);
            LocalDateTime removedDate = bookings.get(barber).remove(userName);
            sendTextMessage(chatId, "Запис видалено: " + userName + " - " + formatDateTime(removedDate));
        } else {
            sendTextMessage(chatId, "Помилка: запис для користувача " + userName + " не знайдено.");
        }
    }

    private void processDeletionRequest(long chatId, String userInput) {
        isWaitingForName = false;
        try {
            String[] parts = userInput.split(" ", 2);
            if (parts.length < 2) {
                sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз.");
                return;
            }
            selectedBarber = parts[0];
            String userName = parts[1];
            deleteUserRecord(userName, chatId);
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз.");
        }
    }

    private void processChangeRequest(long chatId, String userInput) {
        try {
            String[] parts = userInput.split(" ", 4);
            if (parts.length < 4) {
                sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз.");
                return;
            }
            String barber = parts[0];
            String username = parts[1];
            LocalDateTime newDateTime = validateAndParseDate(parts[2] + " " + parts[3], chatId);
            if (newDateTime == null) return;

            if (bookings.containsKey(barber) && bookings.get(barber).containsKey(username)) {
                bookings.get(barber).put(username, newDateTime);
                sendTextMessage(chatId, "Запис для користувача " + username + " у барбера " + barber + " змінено на: " + formatDateTime(newDateTime));
            } else {
                sendTextMessage(chatId, "Користувача не знайдено у барбера " + barber);
            }
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз.");
        }
        isWaitingForChanges = false;
    }

    private boolean isAdmin(long userId) {
        return adminIds.contains(userId);
    }

    private boolean isPastDate(LocalDateTime dateTime) {
        return dateTime.isBefore(LocalDateTime.now());
    }

    private boolean hasTimeConflict(LocalDateTime newDateTime) {
        return bookings.values().stream()
                .flatMap(map -> map.values().stream())
                .anyMatch(existingDate -> Math.abs(java.time.Duration.between(newDateTime, existingDate).toMinutes()) < 25);
    }

    private String formatAllRecords() {
        if (bookings.isEmpty()) {
            return "Записів поки немає.";
        }

        StringBuilder result = new StringBuilder("Усі записи:");
        bookings.forEach((barber, records) -> {
            result.append("\n\nБарбер: ").append(barber).append("\n");
            records.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(entry -> {
                        String user = entry.getKey();
                        String formattedDate = formatDateTime(entry.getValue());
                        result.append(user).append(" - ").append(formattedDate).append("\n");
                    });
        });

        return result.toString();
    }

    private ReplyKeyboardMarkup createKeyboard(List<List<String>> rows) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);

        List<KeyboardRow> keyboardRows = new ArrayList<>();
        for (List<String> row : rows) {
            KeyboardRow keyboardRow = new KeyboardRow();
            keyboardRow.addAll(row);
            keyboardRows.add(keyboardRow);
        }
        keyboardMarkup.setKeyboard(keyboardRows);
        return keyboardMarkup;
    }

    private void sendKeyboardMessage(long chatId, String text, ReplyKeyboardMarkup keyboardMarkup) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(keyboardMarkup);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendTextMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private String formatBookingConfirmation(LocalDateTime dateTime) {
        return "\u2705 Ви успішно записались на: " + formatDateTime(dateTime) + "\n" +
                "— Закиньте оплату:\n" +
                "4149499995087812 Приват\n" +
                "5375411410802206 Моно\n" +
                "100₴\n" +
                "\uD83D\uDD25 Якщо бажаєте уникнути черги, сплатіть >= 150₴!";
    }

    private LocalDateTime validateAndParseDate(String input, long chatId) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime dateTime = LocalDateTime.parse(input, formatter);

            if (isPastDate(dateTime)) {
                sendTextMessage(chatId, "Помилка: неможливо записатися на минулу дату. Будь ласка, введіть коректну дату.");
                return null;
            }

            if (hasTimeConflict(dateTime)) {
                sendTextMessage(chatId, "Мінімум 25 хвилин між стрижками. Оберіть інший час.");
                return null;
            }

            return dateTime;
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат дати. Спробуйте ще раз.");
            return null;
        }
    }

    private String formatDateTime(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return dateTime.format(formatter);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }
}
