package ma.mobility.abrid.api.dto;

import java.util.List;

public record PlanTripResponse(
        List<JourneyDto> journeys,
        StationDto fromStation,
        StationDto toStation,
        String travelDate
) {}
