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

/**
 * Service layer for all interactions with the Google Calendar API v3.
 *
 * <p>Each method builds a per-instructor {@link Calendar} client authenticated with the
 * instructor's stored OAuth 2.0 access token, then performs a single Calendar API call.
 * Events are always created in the instructor's primary calendar ({@code primary}) and
 * times are anchored to the {@code Europe/Lisbon} timezone.</p>
 *
 * <p><strong>Token lifecycle:</strong> The access token is stored on the {@link Instructor}
 * entity and is valid for ~1 hour after login. If the token has expired, the Calendar API
 * will return a {@code 401} and an {@link IOException} will propagate to the caller. No
 * automatic token refresh is implemented — the instructor must log in again.</p>
 */
@Service
public class GoogleCalendarService {

    /** Application name reported to the Google Calendar API for quota and logging purposes. */
    private static final String APPLICATION_NAME = "Work Timetable Manager";

    /** Target calendar — {@code "primary"} resolves to the instructor's default Google Calendar. */
    private static final String CALENDAR_ID = "primary";

    /** Timezone used for all event date-times; matches the gym's physical location. */
    private static final ZoneId LISBON_ZONE = ZoneId.of("Europe/Lisbon");

    /**
     * Unused formatter constant retained for future use.
     * Active formatting is performed inline in {@link #toEventDateTime}.
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Creates a new Google Calendar event for the given session in the instructor's primary calendar.
     *
     * <p>The event title is assembled by {@link #buildTitle} and the color is set from the
     * session's pre-assigned Google Calendar color ID. Notes are used as the event description.</p>
     *
     * @param instructor the instructor whose calendar will receive the event
     * @param session    the session data to persist as a calendar event
     * @return the Google Calendar event ID of the newly created event, used for future
     *         update and delete operations
     * @throws GeneralSecurityException if TLS setup fails when building the HTTP transport
     * @throws IOException              if the Calendar API call fails (e.g., expired token, network error)
     */
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

    /**
     * Permanently deletes a Google Calendar event by its event ID.
     *
     * <p>Called when the instructor deletes a session from the dashboard or clears an
     * entire week. Errors are expected to be caught and logged by the calling controller
     * without failing the DB-level delete, so the two stores remain loosely consistent.</p>
     *
     * @param instructor     the instructor who owns the event
     * @param googleEventId  the Calendar API event ID to delete
     * @throws GeneralSecurityException if TLS setup fails
     * @throws IOException              if the Calendar API call fails
     */
    public void deleteEvent(Instructor instructor, String googleEventId) throws GeneralSecurityException, IOException {
        Calendar service = buildCalendarService(instructor);
        service.events().delete(CALENDAR_ID, googleEventId).execute();
    }

    /**
     * Updates the title and description of an existing Google Calendar event.
     *
     * <p>Only the summary (title) and description (notes) are modified; the event's
     * time, color, and other fields are left unchanged. This is intentional — time edits
     * are not currently exposed in the UI.</p>
     *
     * @param instructor     the instructor who owns the event
     * @param googleEventId  the Calendar API event ID to update
     * @param session        the session DTO carrying the new title and notes values
     * @throws GeneralSecurityException if TLS setup fails
     * @throws IOException              if the Calendar API call fails
     */
    public void updateEvent(Instructor instructor, String googleEventId, ParsedSessionDTO session) throws GeneralSecurityException, IOException {
        Calendar service = buildCalendarService(instructor);

        // Fetch the existing event to preserve fields not managed by this application.
        Event existing = service.events().get(CALENDAR_ID, googleEventId).execute();
        existing.setSummary(buildTitle(session));
        existing.setDescription(session.getNotes() != null ? session.getNotes() : "");

        service.events().update(CALENDAR_ID, googleEventId, existing).execute();
    }

    /**
     * Assembles the event title from the session's display name and optional class name.
     *
     * <p>Format: {@code "<displayName>"} or {@code "<displayName> | <className>"} when a
     * class name is present. The pipe separator matches the visual style used in the UI.</p>
     *
     * @param session the session whose title should be built
     * @return the formatted event title string
     */
    private String buildTitle(ParsedSessionDTO session) {
        if (session.getClassName() != null && !session.getClassName().isBlank()) {
            return session.getDisplayName() + " | " + session.getClassName();
        }
        return session.getDisplayName();
    }

    /**
     * Constructs a Google Calendar API client authenticated with the instructor's access token.
     *
     * <p>A new {@link Calendar} instance is created on every call rather than being cached,
     * because access tokens are short-lived and per-instructor. Caching would require
     * per-token invalidation logic that is not warranted at the current scale.</p>
     *
     * @param instructor the instructor whose stored token should be used
     * @return an authenticated {@link Calendar} API client
     * @throws GeneralSecurityException if the default trusted TLS transport cannot be initialized
     * @throws IOException              if the credentials adapter setup fails
     */
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

    /**
     * Converts a local date and time into a Google Calendar {@link EventDateTime} in the
     * Lisbon timezone.
     *
     * <p>The Calendar API requires RFC 3339 timestamps with a UTC offset. This method
     * attaches the {@code Europe/Lisbon} zone (which accounts for WET/WEST daylight-saving
     * transitions) and formats accordingly.</p>
     *
     * @param date the calendar date of the session
     * @param time the start or end time of the session
     * @return an {@link EventDateTime} ready for use in a Calendar API event
     */
    private EventDateTime toEventDateTime(LocalDate date, LocalTime time) {
        LocalDateTime ldt = LocalDateTime.of(date, time);
        ZonedDateTime zdt = ldt.atZone(LISBON_ZONE);
        String formatted = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        return new EventDateTime()
            .setDateTime(new com.google.api.client.util.DateTime(formatted))
            .setTimeZone("Europe/Lisbon");
    }
}
