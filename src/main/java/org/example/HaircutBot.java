package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

//670778441
public class HaircutBot extends TelegramLongPollingBot {
    private Map<String, Map<String, LocalDateTime>> bookings = new HashMap<>();
    private final Map<String, String> userBookings = new HashMap<>(); // Tracks which barber a user is booked with
    public static Set<Long> adminIds; // = Set.of(1514302273L, 818667420L, 799128809L);
    private final String botUsername;
    private final String botToken;
    private final String bookingsFile = "bookings.json";
    private String selectedBarber = null;
    private boolean isWaitingForBarber = false;
    private boolean isWaitingForDate = false;
    private boolean isWaitingForName = false;
    private boolean isWaitingForChanges = false;
    private boolean isWaitingForOtherUserBooking = false;
    private final ObjectMapper objectMapper;


    public HaircutBot(String botUsername, String botToken, Set<Long> adminIds) {
        this.botUsername = botUsername;
        this.botToken = botToken;
        HaircutBot.adminIds = adminIds;
        objectMapper = new ObjectMapper();
        configureObjectMapper(objectMapper);
        loadBookings();
    }

    @Override
    public void onUpdateReceived(Update update) {
        removeExpiredBookings();
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
                    sendTextMessage(chatId, "Введіть у форматі: Барбер(Різа, Іванов, Дубов) Ім'я(того, кого хочеш змініти) та нову дату в форматі yyyy-MM-dd HH:mm");
                    break;
                case "Видалити запис користувача":
                    isWaitingForName = true;
                    sendTextMessage(chatId, "Введіть барбера та ім'я користувача для видалення. Формат: Барбер(Різа, Іванов, Дубов) Ім'я:");
                    break;
                case "Додати запис за іншого":
                    isWaitingForOtherUserBooking = true;
                    sendTextMessage(chatId, "Введіть дані у форматі: Барбер(Різа, Іванов, Дубов) Ім'я yyyy-MM-dd HH:mm");
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
                    } else if (isWaitingForOtherUserBooking) {
                        processOtherUserBooking(chatId, messageText);
                        isWaitingForOtherUserBooking = false;
                    } else {
                        sendTextMessage(chatId, "Невідома команда. Спробуйте ще раз.(Вийди в головне меню)");
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
                List.of("Додати запис за іншого", "В головне меню")
        ));
        sendKeyboardMessage(chatId, "Виберіть дію:", keyboardMarkup);
    }

    private void showBarberSelection(long chatId) {
        isWaitingForBarber = true;
        ReplyKeyboardMarkup keyboardMarkup = createKeyboard(List.of(
                List.of("Різа", "Іванов", "Дубов"),
                List.of("В головне меню")
        ));
        sendKeyboardMessage(chatId, "Оберіть барбера:", keyboardMarkup);
    }

    private void promptForBooking(long chatId) {
        sendTextMessage(chatId, "Введіть дату (формат: 2024-12-21 15:00)");
    }

    private void handleUserInput(long chatId, String userName, String input) {
        LocalDateTime dateTime = null;
        try {
            if (userBookings.containsKey(userName)) {
                sendTextMessage(chatId, "Ви вже записані до барбера " + userBookings.get(userName) + ". Неможливо записатись повторно.");
                showMainMenu(chatId);
                return;
            }
            dateTime = validateAndParseDate(input, chatId);
            if (dateTime == null) return;
            bookings.computeIfAbsent(selectedBarber, k -> new HashMap<>()).put(userName, dateTime);
            userBookings.put(userName, selectedBarber);
            saveBookings();
            notifyBarber(selectedBarber, userName, dateTime);
            sendTextMessage(chatId, formatBookingConfirmation(dateTime));
            showMainMenu(chatId);
            isWaitingForDate = false;
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат дати. Напиши ще раз дату .");
            isWaitingForDate = true;
        }
    }

    private void notifyBarber(String barber, String userName, LocalDateTime dateTime) {
        // Определяем идентификаторы чатов барберов
        Map<String, Long> barberChatIds = Map.of(
                "Різа", 1514302273L, // Замените на фактические ID барберов
                "Іванов", 799128809L,
                "Дубов", 670778441L
        );

        Long barberChatId = barberChatIds.get(barber);

        if (barberChatId == null) {
            System.out.println("Идентификатор чата для барбера " + barber + " не найден.");
            return;
        }

        String message = "Новый клиент записался на стрижку:\n" +
                "Клиент: " + userName + "\n" +
                "Дата: " + formatDateTime(dateTime);

        sendTextMessage(barberChatId, message);
    }

    private void deleteUserRecord(String userName, long chatId) {
        boolean recordFound = false;

        for (Map.Entry<String, Map<String, LocalDateTime>> barberEntry : bookings.entrySet()) {
            String barber = barberEntry.getKey();
            Map<String, LocalDateTime> barberBookings = barberEntry.getValue();

            if (barberBookings.containsKey(userName)) {
                LocalDateTime removedDate = barberBookings.remove(userName);
                recordFound = true;

                if (barberBookings.isEmpty()) {
                    bookings.remove(barber);
                }

                userBookings.remove(userName);
                saveBookings();
                sendTextMessage(chatId, "Запись удалена: " + userName + " у барбера " + barber + " на " + formatDateTime(removedDate));
                break;
            }
        }

        if (!recordFound) {
            sendTextMessage(chatId, "Запись для пользователя " + userName + " не найдена.");
        }
    }

    private void processDeletionRequest(long chatId, String userInput) {
        isWaitingForName = false;
        try {
            String[] parts = userInput.split(" ", 2);
            if (parts.length < 2) {
                sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз. Чи вийди в головне меню");
                return;
            }
            selectedBarber = parts[0];
            String userName = parts[1];
            deleteUserRecord(userName, chatId);
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз. Чи вийди в головне меню");
        }
    }

    private void removeExpiredBookings() {
        LocalDateTime now = LocalDateTime.now();

        // Итерация по всем барберам
        for (Map.Entry<String, Map<String, LocalDateTime>> barberEntry : bookings.entrySet()) {
            String barber = barberEntry.getKey();
            Map<String, LocalDateTime> barberBookings = barberEntry.getValue();

            // Удаляем записи, где время записи + 15 минут меньше текущего времени
            barberBookings.entrySet().removeIf(entry -> entry.getValue().plusMinutes(15).isBefore(now));

            // Если у барбера больше нет записей, удаляем его из списка
            if (barberBookings.isEmpty()) {
                bookings.remove(barber);
            }
        }

        // Сохраняем обновленные записи
        saveBookings();
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
                saveBookings();
                sendTextMessage(chatId, "Запис для користувача " + username + " у барбера " + barber + " змінено на: " + formatDateTime(newDateTime));
                showMainMenu(chatId);
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
        if ("Дубов".equals(selectedBarber)) {
            return false;
        }
        if (selectedBarber != null) {
            return bookings.values().stream()
                    .flatMap(map -> map.values().stream())
                    .anyMatch(existingDate -> Math.abs(Duration.between(newDateTime, existingDate).toMinutes()) < 15);
        }
        return false;
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
        return "✅ Ви успішно записались на: " + formatDateTime(dateTime) + "\n" +
                "❗В нас стрижуться по передоплаті:\n" +
                "\uD83D\uDE0DРіза:\n" +
                "          4149499995087812 Privat\n" +
                "          5375411410802206 Monobank\n" +
                "\uD83E\uDD70Ілля:\n" +
                "          4149499995087820 Privat\n" +
                "\uD83D\uDE18ІлляДубов:\n" +
                "          5375235104443930 A-Bank\n" +
                "          4149499990441709 Privat\n"+
                "— Стандартна ціна: 100₴\n" +
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
                sendTextMessage(chatId, "Мінімум 15 хвилин між стрижками. Оберіть інший час.");
                return null;
            }

            return dateTime;
        } catch (Exception e) {
            sendTextMessage(chatId, "Невірний формат дати. Спробуйте ще раз.");
            return null;
        }
    }

    private void processOtherUserBooking(long chatId, String userInput) {
        try {
            String[] parts = userInput.split(" ", 4); // Разделение ввода на 4 части
            if (parts.length < 4) {
                sendTextMessage(chatId, "Невірний формат. Спробуйте ще раз.");
                return;
            }

            String barber = parts[0]; // Барбер
            String otherUserName = parts[1]; // Имя другого пользователя
            LocalDateTime dateTime = validateAndParseDate(parts[2] + " " + parts[3], chatId); // Дата и время

            if (dateTime == null) return; // Проверка формата даты

            // Проверка на существование записи
            if (bookings.containsKey(barber) && bookings.get(barber).containsKey(otherUserName)) {
                sendTextMessage(chatId, "Користувач " + otherUserName + " вже має запис до барбера " + barber + ".");
                return;
            }

            // Добавление записи
            bookings.computeIfAbsent(barber, k -> new HashMap<>()).put(otherUserName, dateTime);
            saveBookings();
            sendTextMessage(chatId, "Запис для користувача " + otherUserName + " до барбера " + barber + " успішно додано на: " + formatDateTime(dateTime));
        } catch (Exception e) {
            sendTextMessage(chatId, "Помилка при додаванні запису. Перевірте формат і спробуйте знову.");
        }
    }


    private String formatDateTime(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return dateTime.format(formatter);
    }

    private void saveBookings() {
        try {
            bookings.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            objectMapper.writeValue(new File(bookingsFile), bookings);  // Сохранение данных в файл
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadBookings() {
        try {
            File file = new File(bookingsFile);
            if (file.exists()) {
                bookings = objectMapper.readValue(file, new TypeReference<Map<String, Map<String, LocalDateTime>>>() {
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void configureObjectMapper(ObjectMapper objectMapper) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer());  // Регистрация сериализатора
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer());  // Регистрация десериализатора
        objectMapper.registerModule(module);
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