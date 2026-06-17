package ma.mobility.abrid.api.dto;

public record LegDto(
        StationDto fromStation,
        StationDto toStation,
        String departure,
        String arrival,
        int durationMinutes,
        String mode,
        String routeName,
        String headsign,
        Double fareMad
) {}
