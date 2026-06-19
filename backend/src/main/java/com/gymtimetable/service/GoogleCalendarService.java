package com.gymtimetable.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.gymtimetable.dto.ParsedSessionDTO;
import com.gymtimetable.model.Instructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Service
public class GoogleCalendarService {

    private static final String APPLICATION_NAME = "Work Timetable Manager";
    private static final String CALENDAR_ID = "primary";
    private static final ZoneId LISBON_ZONE = ZoneId.of("Europe/Lisbon");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public String createEvent(Instructor instructor, ParsedSessionDTO session) throws GeneralSecurityException, IOException {
        Calendar service = buildCalendarService(instructor);

        String title = buildTitle(session);

        Event event = new Event()
            .setSummary(title)
            .setDescription(session.getNotes() != null ? session.getNotes() : "")
            .setColorId(String.valueOf(session.getGoogleCalendarColorId()));

        EventDateTime start = toEventDateTime(session.getSessionDate(), session.getStartTime());
        EventDateTime end = toEventDateTime(session.getSessionDate(), session.getEndTime());
        event.setStart(start);
        event.setEnd(end);

        Event created = service.events().insert(CALENDAR_ID, event).execute();
        return created.getId();
    }

    public void deleteEvent(Instructor instructor, String googleEventId) throws GeneralSecurityException, IOException {
        Calendar service = buildCalendarService(instructor);
        service.events().delete(CALENDAR_ID, googleEventId).execute();
    }

    public void updateEvent(Instructor instructor, String googleEventId, ParsedSessionDTO session) throws GeneralSecurityException, IOException {
        Calendar service = buildCalendarService(instructor);

        Event existing = service.events().get(CALENDAR_ID, googleEventId).execute();
        existing.setSummary(buildTitle(session));
        existing.setDescription(session.getNotes() != null ? session.getNotes() : "");

        service.events().update(CALENDAR_ID, googleEventId, existing).execute();
    }

    private String buildTitle(ParsedSessionDTO session) {
        if (session.getClassName() != null && !session.getClassName().isBlank()) {
            return session.getDisplayName() + " | " + session.getClassName();
        }
        return session.getDisplayName();
    }

    private Calendar buildCalendarService(Instructor instructor) throws GeneralSecurityException, IOException {
        Date expiry = instructor.getTokenExpiry() != null
            ? Date.from(instructor.getTokenExpiry().atZone(ZoneId.systemDefault()).toInstant())
            : null;

        AccessToken token = new AccessToken(instructor.getAccessToken(), expiry);
        GoogleCredentials credentials = GoogleCredentials.create(token);

        return new Calendar.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            new HttpCredentialsAdapter(credentials)
        ).setApplicationName(APPLICATION_NAME).build();
    }

    private EventDateTime toEventDateTime(LocalDate date, LocalTime time) {
        LocalDateTime ldt = LocalDateTime.of(date, time);
        ZonedDateTime zdt = ldt.atZone(LISBON_ZONE);
        String formatted = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        return new EventDateTime()
            .setDateTime(new com.google.api.client.util.DateTime(formatted))
            .setTimeZone("Europe/Lisbon");
    }
}
