package com.gymtimetable.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedSessionDTO {
    private Long id;
    private LocalDate sessionDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String sessionType;
    private String sessionTypeAbbrev;
    private String displayName;
    private String className;
    private String notes;
    private int googleCalendarColorId;
    private boolean overlapping;
    @Builder.Default
    private boolean selected = true;
    private String googleEventId;
    private boolean exportedToCalendar;
}
