package com.gymtimetable.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "timetable_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id", nullable = false)
    private Instructor instructor;

    @Column(nullable = false)
    private LocalDate weekStartDate;

    @Column(nullable = false)
    private LocalDate sessionDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionType sessionType;

    @Column(nullable = false)
    private String sessionTypeAbbrev;

    private String className;

    private String location;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private String googleEventId;

    @Column(nullable = false)
    @Builder.Default
    private boolean exportedToCalendar = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean overlapping = false;

    public enum SessionType {
        SALA_MUSCULACAO,
        SOBREPOSICAO_SALA,
        AVALIACAO_FISICA,
        PERSONAL_TRAINING,
        TRANSICAO,
        ADMINISTRATIVO,
        VIGILANCIA_PISCINA,
        NATACAO,
        AULAS_GRUPO
    }
}
