package ma.mobility.abrid.api.dto;

import java.util.List;

public record JourneyDto(
        List<LegDto> legs,
        int totalDurationMinutes,
        int nbTransfers,
        String departure,
        String arrival,
        Double totalFareMad,
        String dataSource,
        String dataFreshnessDate
) {}
